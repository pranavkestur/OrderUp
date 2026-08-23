#!/usr/bin/env python3
"""
Quick Live Test - Places REAL orders with simulated market data
Supports both quick test mode (1 order) and continuous mode (like production)

Usage:
  python run_live_test.py              # Quick test - stops after 1 order
  python run_live_test.py --continuous # Runs like production (until Ctrl+C)
  python run_live_test.py --scenario sell  # Test SELL signal instead of BUY
"""

import os
import sys
import socket
import time
from datetime import datetime

# Force IPv4
original_getaddrinfo = socket.getaddrinfo
def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses
socket.getaddrinfo = forced_ipv4_getaddrinfo

from dotenv import load_dotenv
load_dotenv()

from kiteconnect import KiteConnect
from realtime_simulator import RealTimeSimulator
from indicators import (
    calculate_cci, calculate_williams_r,
    detect_cci_crossover, detect_williams_r_crossover
)

# Parse command line arguments
continuous_mode = '--continuous' in sys.argv or '-c' in sys.argv
scenario = 'cci_buy'  # Default

if '--scenario' in sys.argv:
    idx = sys.argv.index('--scenario')
    if idx + 1 < len(sys.argv):
        scenario_arg = sys.argv[idx + 1].lower()
        scenario_map = {
            'buy': 'cci_buy',
            'sell': 'cci_sell',
            'cci_buy': 'cci_buy',
            'cci_sell': 'cci_sell',
            'wr_buy': 'wr_buy',
            'wr_sell': 'wr_sell',
            'random': 'random'
        }
        scenario = scenario_map.get(scenario_arg, 'cci_buy')

mode_str = "CONTINUOUS (like production)" if continuous_mode else "QUICK TEST (1 order then stop)"

print(f"""
╔══════════════════════════════════════════════════════════════════╗
║        LIVE ORDER TEST WITH SIMULATED MARKET DATA                 ║
╠══════════════════════════════════════════════════════════════════╣
║  • Market data: SIMULATED (fake prices)                           ║
║  • Orders: REAL (will be sent to Kite)                            ║
║  • If markets closed: GTT orders will be placed                   ║
╠══════════════════════════════════════════════════════════════════╣
║  Mode: {mode_str:<54} ║
║  Scenario: {scenario:<51} ║
╚══════════════════════════════════════════════════════════════════╝
""")

# Connect to Kite
api_key = os.getenv('KITE_API_KEY')
access_token = os.getenv('KITE_ACCESS_TOKEN')
symbol = os.getenv('STOCK_SYMBOL', 'RELIANCE')
exchange = os.getenv('EXCHANGE', 'NSE')
quantity = int(os.getenv('QUANTITY', 1))
fallback_price = float(os.getenv('FALLBACK_PRICE', 3000))

if not api_key or not access_token:
    print("❌ Missing KITE_API_KEY or KITE_ACCESS_TOKEN in .env")
    print("   Run: python orderup.py to authenticate first")
    exit(1)

kite = KiteConnect(api_key=api_key)
kite.set_access_token(access_token)

# Verify connection
try:
    profile = kite.profile()
    print(f"✅ Connected as: {profile['user_name']} ({profile['user_id']})")
except Exception as e:
    print(f"❌ Connection failed: {e}")
    print("   Your token may have expired. Run: python orderup.py")
    exit(1)

print(f"\n📊 Trading Config:")
print(f"   Symbol: {symbol}")
print(f"   Exchange: {exchange}")
print(f"   Quantity: {quantity}")
print(f"   Fallback Price: ₹{fallback_price}")

print(f"\n🎮 Scenario: {scenario}")
if 'buy' in scenario:
    print(f"   This will trigger a BUY signal within ~15-30 seconds")
else:
    print(f"   This will trigger a SELL signal within ~15-30 seconds")

if continuous_mode:
    print(f"\n⚠️  CONTINUOUS MODE: Will keep running and placing orders!")
    print(f"   Press Ctrl+C to stop")
else:
    print(f"\n📌 QUICK TEST MODE: Will stop after placing 1 order")
    print(f"   Use --continuous flag to run like production")

# Initialize simulator
simulator = RealTimeSimulator(base_price=fallback_price, symbol=symbol)
simulator.set_scenario(scenario)
simulator._generate_initial_history(50)

print(f"\n" + "-" * 70)
print(f"{'Time':<12} {'Price':>10} {'CCI':>10} {'W%R':>10} {'Signal':>12}")
print("-" * 70)

# Track signals
last_cci_signal = None
last_wr_signal = None
orders_placed = []
max_orders = float('inf') if continuous_mode else 1  # Unlimited in continuous mode
check_count = 0

try:
    while len(orders_placed) < max_orders:
        # Generate new candle
        simulator.generate_candle()
        df = simulator.get_historical_data()

        if len(df) < 25:
            time.sleep(1)
            continue

        # Calculate indicators
        df['cci'] = calculate_cci(df, period=20)
        df['williams_r'] = calculate_williams_r(df, period=14)

        cci_values = df['cci'].dropna()
        wr_values = df['williams_r'].dropna()

        if len(cci_values) < 2:
            time.sleep(1)
            continue

        current_price = df['close'].iloc[-1]
        current_cci = cci_values.iloc[-1]
        current_wr = wr_values.iloc[-1] if len(wr_values) > 0 else 0

        # Check for signals
        cci_signal = detect_cci_crossover(cci_values)

        signal_str = ""
        if cci_signal['signal']:
            signal_str = cci_signal['signal']

        timestamp = datetime.now().strftime("%H:%M:%S")
        print(f"{timestamp:<12} {current_price:>10.2f} {current_cci:>10.2f} {current_wr:>10.2f} {signal_str:>12}")

        # Place order on signal
        if cci_signal['signal'] and cci_signal['signal'] != last_cci_signal:
            last_cci_signal = cci_signal['signal']

            print(f"\n🔔 SIGNAL DETECTED: CCI {cci_signal['signal']}")
            print(f"   CCI Value: {current_cci:.2f}")

            if cci_signal['signal'] == 'BUY':
                # Try market order first
                try:
                    order_id = kite.place_order(
                        variety=kite.VARIETY_REGULAR,
                        exchange=exchange,
                        tradingsymbol=symbol,
                        transaction_type=kite.TRANSACTION_TYPE_BUY,
                        quantity=quantity,
                        order_type=kite.ORDER_TYPE_MARKET,
                        product=kite.PRODUCT_CNC
                    )
                    print(f"\n🟢 MARKET BUY ORDER PLACED!")
                    print(f"   Order ID: {order_id}")
                    orders_placed.append({'type': 'MARKET_BUY', 'id': order_id})

                except Exception as e:
                    if "Markets are closed" in str(e) or "market closed" in str(e).lower():
                        print(f"\n⏰ Markets closed. Placing GTT order instead...")

                        # Place GTT
                        trigger_price = round(fallback_price * 0.99, 1)
                        limit_price = round(fallback_price * 1.01, 1)

                        gtt_id = kite.place_gtt(
                            trigger_type=kite.GTT_TYPE_SINGLE,
                            tradingsymbol=symbol,
                            exchange=exchange,
                            trigger_values=[trigger_price],
                            last_price=fallback_price,
                            orders=[{
                                "transaction_type": kite.TRANSACTION_TYPE_BUY,
                                "quantity": quantity,
                                "order_type": kite.ORDER_TYPE_LIMIT,
                                "product": kite.PRODUCT_CNC,
                                "price": limit_price
                            }]
                        )
                        print(f"\n🟢 GTT BUY ORDER PLACED!")
                        print(f"   GTT ID: {gtt_id}")
                        print(f"   Trigger: ₹{trigger_price}")
                        print(f"   Limit: ₹{limit_price}")
                        orders_placed.append({'type': 'GTT_BUY', 'id': gtt_id})
                    else:
                        print(f"\n❌ ORDER FAILED: {e}")

            elif cci_signal['signal'] == 'SELL':
                # Try market order first
                try:
                    order_id = kite.place_order(
                        variety=kite.VARIETY_REGULAR,
                        exchange=exchange,
                        tradingsymbol=symbol,
                        transaction_type=kite.TRANSACTION_TYPE_SELL,
                        quantity=quantity,
                        order_type=kite.ORDER_TYPE_MARKET,
                        product=kite.PRODUCT_CNC
                    )
                    print(f"\n🔴 MARKET SELL ORDER PLACED!")
                    print(f"   Order ID: {order_id}")
                    orders_placed.append({'type': 'MARKET_SELL', 'id': order_id})

                except Exception as e:
                    if "Markets are closed" in str(e) or "market closed" in str(e).lower():
                        print(f"\n⏰ Markets closed. Placing GTT order instead...")

                        trigger_price = round(fallback_price * 1.01, 1)
                        limit_price = round(fallback_price * 0.99, 1)

                        gtt_id = kite.place_gtt(
                            trigger_type=kite.GTT_TYPE_SINGLE,
                            tradingsymbol=symbol,
                            exchange=exchange,
                            trigger_values=[trigger_price],
                            last_price=fallback_price,
                            orders=[{
                                "transaction_type": kite.TRANSACTION_TYPE_SELL,
                                "quantity": quantity,
                                "order_type": kite.ORDER_TYPE_LIMIT,
                                "product": kite.PRODUCT_CNC,
                                "price": limit_price
                            }]
                        )
                        print(f"\n🔴 GTT SELL ORDER PLACED!")
                        print(f"   GTT ID: {gtt_id}")
                        print(f"   Trigger: ₹{trigger_price}")
                        print(f"   Limit: ₹{limit_price}")
                        orders_placed.append({'type': 'GTT_SELL', 'id': gtt_id})
                    else:
                        print(f"\n❌ ORDER FAILED: {e}")

        time.sleep(1)

except KeyboardInterrupt:
    print("\n\n🛑 Stopped by user")

print("\n" + "=" * 70)
print("TEST COMPLETE")
print("=" * 70)

if orders_placed:
    print(f"\n✅ Orders placed: {len(orders_placed)}")
    for order in orders_placed:
        print(f"   • {order['type']}: {order['id']}")
    print(f"\n👉 Check your Kite app:")
    print(f"   • Market orders: Orders tab")
    print(f"   • GTT orders: Orders -> GTT tab")
else:
    print("\n⚠️  No orders were placed")

print(f"""
┌─────────────────────────────────────────────────────────────────┐
│ Command Options:                                                 │
│   python run_live_test.py              # Quick test (1 order)    │
│   python run_live_test.py --continuous # Like production         │
│   python run_live_test.py --scenario sell  # Test SELL signal    │
│   python run_live_test.py --scenario random # Random movement    │
└─────────────────────────────────────────────────────────────────┘
""")

