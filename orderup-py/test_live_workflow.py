#!/usr/bin/env python3
"""
Live Workflow Test Script
Tests the complete OrderUp trading workflow as it would run during market hours.

This script demonstrates:
1. Daily authentication flow
2. Market data monitoring
3. Indicator calculation
4. Signal detection
5. Order placement (real orders or GTT if markets closed)
"""

import os
import socket
import sys
import time
from datetime import datetime

# Force IPv4 for Kite API compatibility
original_getaddrinfo = socket.getaddrinfo
def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses
socket.getaddrinfo = forced_ipv4_getaddrinfo

from dotenv import load_dotenv
load_dotenv()


def print_banner():
    print("""
╔══════════════════════════════════════════════════════════════════╗
║                    ORDERUP LIVE WORKFLOW TEST                     ║
║                                                                   ║
║  This tests the COMPLETE trading workflow as it runs during      ║
║  market hours. Choose your test mode below.                       ║
╚══════════════════════════════════════════════════════════════════╝
    """)


def print_workflow_diagram():
    print("""
┌─────────────────────────────────────────────────────────────────┐
│                    LIVE TRADING WORKFLOW                         │
└─────────────────────────────────────────────────────────────────┘

   START (Each Trading Day)
         │
         ▼
   ┌─────────────────────────────────┐
   │ 1. AUTHENTICATION               │
   │    • Open browser for login     │
   │    • Capture OAuth token        │
   │    • Save token for the day     │
   └─────────────────────────────────┘
         │
         ▼
   ┌─────────────────────────────────┐
   │ 2. MARKET MONITORING            │
   │    • Check every 5 minutes      │
   │    • Fetch latest price data    │
   │    • Build OHLC candles         │
   └─────────────────────────────────┘
         │
         ▼
   ┌─────────────────────────────────┐
   │ 3. INDICATOR CALCULATION        │
   │    • CCI (20-period)            │
   │    • Williams %R (14-period)    │
   └─────────────────────────────────┘
         │
         ▼
   ┌─────────────────────────────────┐
   │ 4. SIGNAL DETECTION             │
   │    CCI > +100 → SELL            │
   │    CCI < -100 → BUY             │
   │    W%R > -20 → SELL             │
   │    W%R < -80 → BUY              │
   └─────────────────────────────────┘
         │
         ▼ (If signal detected)
   ┌─────────────────────────────────┐
   │ 5. ORDER PLACEMENT              │
   │    • Markets Open → Market Ord  │
   │    • Markets Closed → GTT Order │
   └─────────────────────────────────┘
         │
         ▼
   Loop back to step 2 (every 5 min)
    """)


def test_authentication():
    """Test the authentication flow"""
    print("\n" + "=" * 60)
    print("TEST 1: AUTHENTICATION FLOW")
    print("=" * 60)

    from kiteconnect import KiteConnect

    api_key = os.getenv('KITE_API_KEY')
    access_token = os.getenv('KITE_ACCESS_TOKEN')

    if not api_key:
        print("❌ KITE_API_KEY not found in .env file")
        return False

    kite = KiteConnect(api_key=api_key)

    if not access_token:
        print("⚠️  No access token found. Need to authenticate...")
        print(f"\n📎 Login URL: {kite.login_url()}")
        print("\nTo authenticate, run: python orderup.py")
        return False

    kite.set_access_token(access_token)

    try:
        profile = kite.profile()
        print(f"\n✅ AUTHENTICATION SUCCESSFUL")
        print(f"   User: {profile['user_name']}")
        print(f"   User ID: {profile['user_id']}")
        print(f"   Email: {profile['email']}")
        return True
    except Exception as e:
        print(f"\n❌ AUTHENTICATION FAILED: {e}")
        print("   Token may have expired. Run: python orderup.py")
        return False


def test_indicator_calculation():
    """Test indicator calculation with simulated data"""
    print("\n" + "=" * 60)
    print("TEST 2: INDICATOR CALCULATION")
    print("=" * 60)

    from realtime_simulator import RealTimeSimulator
    from indicators import calculate_cci, calculate_williams_r

    simulator = RealTimeSimulator(base_price=3000.0, symbol="RELIANCE")
    simulator._generate_initial_history(50)

    df = simulator.get_historical_data()

    # Calculate indicators
    df['cci'] = calculate_cci(df, period=20)
    df['williams_r'] = calculate_williams_r(df, period=14)

    latest = df.iloc[-1]

    print(f"\n✅ INDICATORS CALCULATED")
    print(f"   Latest Close: ₹{latest['close']:.2f}")
    print(f"   CCI (20): {latest['cci']:.2f}")
    print(f"   Williams %R (14): {latest['williams_r']:.2f}")

    # Interpret
    if latest['cci'] > 100:
        print(f"   → CCI in OVERBOUGHT zone (potential SELL)")
    elif latest['cci'] < -100:
        print(f"   → CCI in OVERSOLD zone (potential BUY)")
    else:
        print(f"   → CCI in NEUTRAL zone")

    if latest['williams_r'] > -20:
        print(f"   → W%R in OVERBOUGHT zone (potential SELL)")
    elif latest['williams_r'] < -80:
        print(f"   → W%R in OVERSOLD zone (potential BUY)")
    else:
        print(f"   → W%R in NEUTRAL zone")

    return True


def test_signal_detection():
    """Test signal detection with forced scenarios"""
    print("\n" + "=" * 60)
    print("TEST 3: SIGNAL DETECTION")
    print("=" * 60)

    from realtime_simulator import RealTimeSimulator
    from indicators import (
        calculate_cci, calculate_williams_r,
        detect_cci_crossover, detect_williams_r_crossover
    )

    signals_found = []

    # Test CCI BUY signal
    print("\n📊 Forcing CCI BUY scenario (price dropping)...")
    simulator = RealTimeSimulator(base_price=3000.0, symbol="RELIANCE")
    simulator.set_scenario("cci_buy")
    simulator._generate_initial_history(50)

    for _ in range(20):  # Generate enough candles for signal
        simulator.generate_candle()

    df = simulator.get_historical_data()
    df['cci'] = calculate_cci(df, period=20)
    cci_values = df['cci'].dropna()

    signal = detect_cci_crossover(cci_values)
    if signal['signal']:
        print(f"   ✅ Signal detected: {signal['signal']}")
        print(f"      CCI value: {signal['cci_value']:.2f}")
        signals_found.append(f"CCI {signal['signal']}")
    else:
        print(f"   ⚠️  No signal yet (CCI: {cci_values.iloc[-1]:.2f})")

    # Test Williams %R SELL signal
    print("\n📊 Forcing Williams %R SELL scenario (price rising)...")
    simulator = RealTimeSimulator(base_price=3000.0, symbol="RELIANCE")
    simulator.set_scenario("wr_sell")
    simulator._generate_initial_history(50)

    for _ in range(20):
        simulator.generate_candle()

    df = simulator.get_historical_data()
    df['williams_r'] = calculate_williams_r(df, period=14)
    wr_values = df['williams_r'].dropna()

    signal = detect_williams_r_crossover(wr_values)
    if signal['signal']:
        print(f"   ✅ Signal detected: {signal['signal']}")
        print(f"      W%R value: {signal['williams_r_value']:.2f}")
        signals_found.append(f"W%R {signal['signal']}")
    else:
        print(f"   ⚠️  No signal yet (W%R: {wr_values.iloc[-1]:.2f})")

    print(f"\n✅ SIGNAL DETECTION TEST COMPLETE")
    print(f"   Signals found: {len(signals_found)}")

    return True


def test_order_placement_simulation():
    """Test order placement in simulation mode (no real orders)"""
    print("\n" + "=" * 60)
    print("TEST 4: ORDER PLACEMENT (SIMULATION)")
    print("=" * 60)

    from orderup import OrderUpBot

    bot = OrderUpBot(test_mode=True)  # Test mode - no real orders

    print("\n🧪 Testing in SIMULATION mode (no real orders placed)...")

    # Test buy order
    print("\n📊 Simulating BUY signal...")
    result = bot.place_buy_order("Test: CCI crossed below -100")

    # Test sell order
    print("\n📊 Simulating SELL signal...")
    result = bot.place_sell_order("Test: CCI crossed above +100")

    print(f"\n✅ ORDER SIMULATION TEST COMPLETE")
    return True


def run_live_monitoring_demo(duration_seconds=30):
    """Run a live monitoring demo with simulated data"""
    print("\n" + "=" * 60)
    print("TEST 5: LIVE MONITORING DEMO")
    print("=" * 60)
    print(f"\nRunning for {duration_seconds} seconds (simulated data)")
    print("Press Ctrl+C to stop early\n")

    from realtime_simulator import RealTimeSimulator
    from indicators import (
        calculate_cci, calculate_williams_r,
        detect_cci_crossover, detect_williams_r_crossover
    )

    simulator = RealTimeSimulator(base_price=3000.0, symbol="RELIANCE")
    simulator.set_scenario("random")
    simulator._generate_initial_history(50)

    print(f"{'Time':<12} {'Price':>10} {'CCI':>10} {'W%R':>10} {'Signal':>15}")
    print("-" * 60)

    start_time = time.time()

    try:
        while (time.time() - start_time) < duration_seconds:
            # Generate new candle
            simulator.generate_candle()
            df = simulator.get_historical_data()

            # Calculate indicators
            df['cci'] = calculate_cci(df, period=20)
            df['williams_r'] = calculate_williams_r(df, period=14)

            cci_values = df['cci'].dropna()
            wr_values = df['williams_r'].dropna()

            if len(cci_values) < 2 or len(wr_values) < 2:
                time.sleep(1)
                continue

            current_cci = cci_values.iloc[-1]
            current_wr = wr_values.iloc[-1]
            current_price = df['close'].iloc[-1]

            # Check for signals
            cci_signal = detect_cci_crossover(cci_values)
            wr_signal = detect_williams_r_crossover(wr_values)

            signal_str = ""
            if cci_signal['signal']:
                signal_str = f"CCI {cci_signal['signal']}"
            elif wr_signal['signal']:
                signal_str = f"W%R {wr_signal['signal']}"

            timestamp = datetime.now().strftime("%H:%M:%S")
            print(f"{timestamp:<12} {current_price:>10.2f} {current_cci:>10.2f} {current_wr:>10.2f} {signal_str:>15}")

            if signal_str:
                print(f"   *** 🔔 SIGNAL: Would place {signal_str.split()[1]} order!")

            time.sleep(1)

    except KeyboardInterrupt:
        print("\n\nStopped by user")

    print(f"\n✅ MONITORING DEMO COMPLETE")
    return True


def main():
    print_banner()

    print("SELECT TEST MODE:")
    print("-" * 40)
    print("1. View workflow diagram only")
    print("2. Test authentication")
    print("3. Test indicator calculation")
    print("4. Test signal detection")
    print("5. Test order simulation (no real orders)")
    print("6. Run live monitoring demo (30 sec)")
    print("7. Run ALL tests (except real orders)")
    print()
    print("8. 🔴 REAL ORDERS: Run live test with REAL API")
    print("   (will place actual orders or GTT)")
    print()
    print("0. Exit")

    choice = input("\nSelect option (0-8): ").strip()

    if choice == "0":
        print("Goodbye!")
        return

    elif choice == "1":
        print_workflow_diagram()

    elif choice == "2":
        test_authentication()

    elif choice == "3":
        test_indicator_calculation()

    elif choice == "4":
        test_signal_detection()

    elif choice == "5":
        test_order_placement_simulation()

    elif choice == "6":
        run_live_monitoring_demo(30)

    elif choice == "7":
        print("\n" + "=" * 60)
        print("RUNNING ALL TESTS")
        print("=" * 60)

        tests = [
            ("Authentication", test_authentication),
            ("Indicator Calculation", test_indicator_calculation),
            ("Signal Detection", test_signal_detection),
            ("Order Simulation", test_order_placement_simulation),
            ("Live Monitoring Demo", lambda: run_live_monitoring_demo(15)),
        ]

        results = []
        for name, test_func in tests:
            try:
                success = test_func()
                results.append((name, "✅ PASS" if success else "❌ FAIL"))
            except Exception as e:
                results.append((name, f"❌ ERROR: {e}"))

        print("\n" + "=" * 60)
        print("TEST RESULTS SUMMARY")
        print("=" * 60)
        for name, result in results:
            print(f"  {name}: {result}")
        print()

    elif choice == "8":
        print("\n" + "=" * 60)
        print("🔴 REAL ORDER TEST")
        print("=" * 60)
        print("\nThis will connect to Kite API and place REAL orders.")
        print("If markets are closed, GTT orders will be placed instead.")
        print()

        confirm = input("Type 'YES' to continue: ").strip()
        if confirm != "YES":
            print("Aborted.")
            return

        print("\nStarting real order test...")
        print("Run: python orderup.py --live-test\n")

        # Actually run it
        os.system("python orderup.py --live-test")

    else:
        print("Invalid option")


if __name__ == "__main__":
    main()

