"""
Live Order Test with Mock Data
Uses simulated market data but places REAL orders via Kite API
"""

import os
import socket
import logging
from datetime import datetime
from dotenv import load_dotenv

# Force IPv4 to avoid IPv6 address issues with Kite IP whitelist
original_getaddrinfo = socket.getaddrinfo

def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses

socket.getaddrinfo = forced_ipv4_getaddrinfo

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Load environment variables
load_dotenv()


def get_kite_client():
    """Initialize and return Kite Connect client"""
    try:
        from kiteconnect import KiteConnect
    except ImportError:
        print("Installing kiteconnect...")
        import subprocess
        subprocess.check_call(['pip', 'install', 'kiteconnect'])
        from kiteconnect import KiteConnect

    api_key = os.getenv('KITE_API_KEY')
    api_secret = os.getenv('KITE_API_SECRET')
    access_token = os.getenv('KITE_ACCESS_TOKEN')

    if not api_key:
        print("\n" + "=" * 60)
        print("KITE API CREDENTIALS REQUIRED")
        print("=" * 60)
        print("\nYou need Kite Connect API credentials.")
        print("Get them from: https://developers.kite.trade/")
        print()
        api_key = input("Enter your API Key: ").strip()
        api_secret = input("Enter your API Secret: ").strip()

        if not api_key or not api_secret:
            raise ValueError("API credentials are required")

    kite = KiteConnect(api_key=api_key)

    if not access_token:
        print("\n" + "-" * 60)
        print("AUTHENTICATION")
        print("-" * 60)
        print(f"\n1. Open this URL in your browser:\n")
        print(f"   {kite.login_url()}")
        print(f"\n2. Login with your Zerodha credentials")
        print(f"\n3. After redirect, copy the 'request_token' from the URL")
        print()

        request_token = input("Enter request_token: ").strip()

        if not request_token:
            raise ValueError("Request token is required")

        # Generate session
        data = kite.generate_session(request_token, api_secret=api_secret)
        access_token = data['access_token']

        print(f"\n✅ Authentication successful!")
        print(f"\n💾 Save this in your .env file for future use:")
        print(f"KITE_API_KEY={api_key}")
        print(f"KITE_API_SECRET={api_secret}")
        print(f"KITE_ACCESS_TOKEN={access_token}")
        print()

    kite.set_access_token(access_token)
    return kite


def place_buy_order(kite, symbol: str, quantity: int = 1, exchange: str = "NSE", limit_price: float = None):
    """Place a real BUY order"""
    try:
        order_id = kite.place_order(
            variety=kite.VARIETY_REGULAR,
            exchange=exchange,
            tradingsymbol=symbol,
            transaction_type=kite.TRANSACTION_TYPE_BUY,
            quantity=quantity,
            order_type=kite.ORDER_TYPE_MARKET,
            product=kite.PRODUCT_CNC  # Delivery order
        )

        print(f"\n🟢 BUY ORDER PLACED SUCCESSFULLY!")
        print(f"   Order ID: {order_id}")
        print(f"   Symbol: {symbol}")
        print(f"   Quantity: {quantity}")
        print(f"   Exchange: {exchange}")

        return order_id

    except Exception as e:
        error_msg = str(e)
        if "Markets are closed" in error_msg:
            print(f"\n⏰ Markets are closed. Placing AMO (After Market Order) instead...")
            try:
                if not limit_price:
                    limit_price = float(input(f"   Enter limit price for {symbol} BUY order: ₹").strip())

                order_id = kite.place_order(
                    variety=kite.VARIETY_AMO,
                    exchange=exchange,
                    tradingsymbol=symbol,
                    transaction_type=kite.TRANSACTION_TYPE_BUY,
                    quantity=quantity,
                    order_type=kite.ORDER_TYPE_LIMIT,
                    price=limit_price,
                    product=kite.PRODUCT_CNC
                )
                print(f"\n🟢 AMO BUY ORDER PLACED SUCCESSFULLY!")
                print(f"   Order ID: {order_id}")
                print(f"   Symbol: {symbol}")
                print(f"   Quantity: {quantity}")
                print(f"   Limit Price: ₹{limit_price}")
                print(f"   Note: Will execute at market open")
                return order_id
            except Exception as amo_error:
                print(f"\n❌ AMO ORDER ALSO FAILED: {amo_error}")
                return None
        else:
            print(f"\n❌ BUY ORDER FAILED: {e}")
            return None


def place_sell_order(kite, symbol: str, quantity: int = 1, exchange: str = "NSE", limit_price: float = None):
    """Place a real SELL order"""
    try:
        order_id = kite.place_order(
            variety=kite.VARIETY_REGULAR,
            exchange=exchange,
            tradingsymbol=symbol,
            transaction_type=kite.TRANSACTION_TYPE_SELL,
            quantity=quantity,
            order_type=kite.ORDER_TYPE_MARKET,
            product=kite.PRODUCT_CNC  # Delivery order
        )

        print(f"\n🔴 SELL ORDER PLACED SUCCESSFULLY!")
        print(f"   Order ID: {order_id}")
        print(f"   Symbol: {symbol}")
        print(f"   Quantity: {quantity}")
        print(f"   Exchange: {exchange}")

        return order_id

    except Exception as e:
        error_msg = str(e)
        if "Markets are closed" in error_msg:
            print(f"\n⏰ Markets are closed. Placing AMO (After Market Order) instead...")
            try:
                if not limit_price:
                    limit_price = float(input(f"   Enter limit price for {symbol} SELL order: ₹").strip())

                order_id = kite.place_order(
                    variety=kite.VARIETY_AMO,
                    exchange=exchange,
                    tradingsymbol=symbol,
                    transaction_type=kite.TRANSACTION_TYPE_SELL,
                    quantity=quantity,
                    order_type=kite.ORDER_TYPE_LIMIT,
                    price=limit_price,
                    product=kite.PRODUCT_CNC
                )
                print(f"\n🔴 AMO SELL ORDER PLACED SUCCESSFULLY!")
                print(f"   Order ID: {order_id}")
                print(f"   Symbol: {symbol}")
                print(f"   Quantity: {quantity}")
                print(f"   Limit Price: ₹{limit_price}")
                print(f"   Note: Will execute at market open")
                return order_id
            except Exception as amo_error:
                print(f"\n❌ AMO ORDER ALSO FAILED: {amo_error}")
                return None
        else:
            print(f"\n❌ SELL ORDER FAILED: {e}")
            return None


def get_order_status(kite, order_id: str):
    """Get status of an order"""
    try:
        orders = kite.orders()
        for order in orders:
            if order['order_id'] == order_id:
                return order
        return None
    except Exception as e:
        print(f"Error fetching order status: {e}")
        return None


def test_order_placement():
    """
    Test real order placement with mock indicator signals
    """
    print("\n" + "=" * 70)
    print("🚀 LIVE ORDER PLACEMENT TEST (with Mock Data)")
    print("=" * 70)
    print("\nThis will place REAL orders using Kite API")
    print("Market data is simulated, but orders are REAL")
    print("-" * 70)

    # Get Kite client
    try:
        kite = get_kite_client()
        print("\n✅ Connected to Kite API")
    except Exception as e:
        print(f"\n❌ Failed to connect: {e}")
        return

    # Verify connection by checking profile
    try:
        profile = kite.profile()
        print(f"   User: {profile['user_name']} ({profile['user_id']})")
        print(f"   Email: {profile['email']}")
    except Exception as e:
        print(f"   Warning: Could not fetch profile - {e}")

    # Mock indicator data (simulating signals)
    print("\n" + "-" * 70)
    print("📊 MOCK INDICATOR DATA")
    print("-" * 70)

    mock_signals = [
        {
            'indicator': 'CCI',
            'previous_value': -95,
            'current_value': -105,
            'signal': 'BUY',
            'reason': 'CCI crossed below -100'
        },
        {
            'indicator': 'CCI',
            'previous_value': 98,
            'current_value': 108,
            'signal': 'SELL',
            'reason': 'CCI crossed above +100'
        },
        {
            'indicator': 'Williams %R',
            'previous_value': -78,
            'current_value': -82,
            'signal': 'BUY',
            'reason': 'Williams %R crossed below -80'
        },
        {
            'indicator': 'Williams %R',
            'previous_value': -18,
            'current_value': -22,
            'signal': 'SELL',
            'reason': 'Williams %R crossed below -20'
        }
    ]

    # Display mock signals
    for i, sig in enumerate(mock_signals, 1):
        emoji = "🟢" if sig['signal'] == 'BUY' else "🔴"
        print(f"\n{i}. {emoji} {sig['indicator']} Signal: {sig['signal']}")
        print(f"   Previous: {sig['previous_value']}, Current: {sig['current_value']}")
        print(f"   Reason: {sig['reason']}")

    # Ask user which test to run
    print("\n" + "-" * 70)
    print("SELECT TEST")
    print("-" * 70)
    print("\n1. Place BUY order (CCI signal)")
    print("2. Place SELL order (CCI signal)")
    print("3. Place BUY order (Williams %R signal)")
    print("4. Place SELL order (Williams %R signal)")
    print("5. Place ALL orders (test all signals)")
    print("0. Exit without placing orders")

    choice = input("\nSelect option (0-5): ").strip()

    if choice == "0":
        print("\nExiting without placing orders.")
        return

    # Get symbol
    symbol = input("\nEnter stock symbol (default: RELIANCE): ").strip().upper() or "RELIANCE"
    quantity = int(input("Enter quantity (default: 1): ").strip() or "1")

    print(f"\n⚠️  About to place REAL order(s) for {quantity} {symbol}")
    confirm = input("Type 'PLACE' to confirm: ").strip()

    if confirm != "PLACE":
        print("\nCancelled.")
        return

    orders_placed = []

    # Execute based on choice
    if choice in ["1", "5"]:
        print(f"\n📊 Mock Signal: CCI crossed below -100 (BUY)")
        order_id = place_buy_order(kite, symbol, quantity)
        if order_id:
            orders_placed.append({'type': 'BUY', 'trigger': 'CCI', 'order_id': order_id})

    if choice in ["2", "5"]:
        print(f"\n📊 Mock Signal: CCI crossed above +100 (SELL)")
        order_id = place_sell_order(kite, symbol, quantity)
        if order_id:
            orders_placed.append({'type': 'SELL', 'trigger': 'CCI', 'order_id': order_id})

    if choice in ["3", "5"]:
        print(f"\n📊 Mock Signal: Williams %R crossed below -80 (BUY)")
        order_id = place_buy_order(kite, symbol, quantity)
        if order_id:
            orders_placed.append({'type': 'BUY', 'trigger': 'Williams %R', 'order_id': order_id})

    if choice in ["4", "5"]:
        print(f"\n📊 Mock Signal: Williams %R crossed below -20 (SELL)")
        order_id = place_sell_order(kite, symbol, quantity)
        if order_id:
            orders_placed.append({'type': 'SELL', 'trigger': 'Williams %R', 'order_id': order_id})

    # Print summary
    print("\n" + "=" * 70)
    print("📋 ORDER SUMMARY")
    print("=" * 70)

    if orders_placed:
        print(f"\nTotal orders placed: {len(orders_placed)}")

        for i, order in enumerate(orders_placed, 1):
            emoji = "🟢" if order['type'] == 'BUY' else "🔴"
            print(f"\n{i}. {emoji} {order['type']} Order")
            print(f"   Order ID: {order['order_id']}")
            print(f"   Trigger: {order['trigger']}")

            # Try to get order status
            status = get_order_status(kite, order['order_id'])
            if status:
                print(f"   Status: {status.get('status', 'UNKNOWN')}")
                print(f"   Price: {status.get('average_price', 'N/A')}")
    else:
        print("\nNo orders were placed.")

    print("\n" + "=" * 70)
    print("✅ Test completed!")
    print("=" * 70)


if __name__ == "__main__":
    test_order_placement()
