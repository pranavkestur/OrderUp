"""
Order Placement Test Module
Tests actual order placement with Kite Connect API

Note: Kite Connect requires a subscription for API access.
This module supports both:
1. Live mode - Places real orders via Kite API
2. Simulation mode - Simulates order placement for testing
"""

import os
import logging
from datetime import datetime
from typing import Optional, Dict, Any
from dotenv import load_dotenv

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class OrderManager:
    """
    Manages order placement with support for both live and simulation modes
    """

    def __init__(self, simulation_mode: bool = True):
        """
        Initialize the Order Manager

        Args:
            simulation_mode: If True, simulates orders without connecting to Kite
        """
        self.simulation_mode = simulation_mode
        self.kite = None
        self.orders_placed = []

        load_dotenv()

        if not simulation_mode:
            self._initialize_kite()

    def _initialize_kite(self):
        """Initialize Kite Connect API"""
        try:
            from kiteconnect import KiteConnect

            api_key = os.getenv('KITE_API_KEY')
            access_token = os.getenv('KITE_ACCESS_TOKEN')

            if not api_key:
                raise ValueError("KITE_API_KEY not found in environment")

            self.kite = KiteConnect(api_key=api_key)

            if access_token:
                self.kite.set_access_token(access_token)
                logger.info("Kite Connect initialized successfully")
            else:
                logger.warning("No access token found. Authentication required.")
                self._prompt_authentication()

        except ImportError:
            logger.error("kiteconnect package not installed. Run: pip install kiteconnect")
            raise
        except Exception as e:
            logger.error(f"Failed to initialize Kite Connect: {e}")
            raise

    def _prompt_authentication(self):
        """Prompt user for Kite authentication"""
        print("\n" + "=" * 60)
        print("KITE AUTHENTICATION REQUIRED")
        print("=" * 60)

        login_url = self.kite.login_url()
        print(f"\n1. Visit this URL:\n   {login_url}")
        print("\n2. Login with your Zerodha credentials")
        print("\n3. Copy the 'request_token' from the redirect URL")

        request_token = input("\nEnter request_token: ").strip()

        if request_token:
            api_secret = os.getenv('KITE_API_SECRET')
            data = self.kite.generate_session(request_token, api_secret=api_secret)
            access_token = data['access_token']
            self.kite.set_access_token(access_token)

            print(f"\n✅ Authentication successful!")
            print(f"\nSave this in your .env file:")
            print(f"KITE_ACCESS_TOKEN={access_token}")
        else:
            raise ValueError("No request token provided")

    def place_order(
        self,
        symbol: str,
        transaction_type: str,  # "BUY" or "SELL"
        quantity: int,
        exchange: str = "NSE",
        order_type: str = "MARKET",
        product: str = "CNC",
        trigger_reason: str = ""
    ) -> Dict[str, Any]:
        """
        Place an order (live or simulated)

        Args:
            symbol: Trading symbol (e.g., "RELIANCE")
            transaction_type: "BUY" or "SELL"
            quantity: Number of shares
            exchange: "NSE" or "BSE"
            order_type: "MARKET" or "LIMIT"
            product: "CNC" (delivery) or "MIS" (intraday)
            trigger_reason: Reason for the order (e.g., "CCI crossed +100")

        Returns:
            Order result dictionary
        """
        order_details = {
            'timestamp': datetime.now().isoformat(),
            'symbol': symbol,
            'exchange': exchange,
            'transaction_type': transaction_type,
            'quantity': quantity,
            'order_type': order_type,
            'product': product,
            'trigger_reason': trigger_reason,
            'mode': 'SIMULATION' if self.simulation_mode else 'LIVE'
        }

        if self.simulation_mode:
            return self._simulate_order(order_details)
        else:
            return self._place_live_order(order_details)

    def _simulate_order(self, order_details: Dict) -> Dict[str, Any]:
        """Simulate order placement"""

        # Generate a fake order ID
        order_id = f"SIM_{datetime.now().strftime('%Y%m%d%H%M%S')}_{len(self.orders_placed) + 1}"

        result = {
            **order_details,
            'order_id': order_id,
            'status': 'COMPLETE',
            'message': 'Order simulated successfully'
        }

        self.orders_placed.append(result)

        logger.info(f"[SIMULATION] {order_details['transaction_type']} order placed")
        logger.info(f"  Symbol: {order_details['symbol']}")
        logger.info(f"  Quantity: {order_details['quantity']}")
        logger.info(f"  Order ID: {order_id}")
        logger.info(f"  Reason: {order_details['trigger_reason']}")

        return result

    def _place_live_order(self, order_details: Dict) -> Dict[str, Any]:
        """Place actual order via Kite API"""

        if not self.kite:
            raise ValueError("Kite API not initialized")

        try:
            # Map transaction type
            if order_details['transaction_type'] == 'BUY':
                txn_type = self.kite.TRANSACTION_TYPE_BUY
            else:
                txn_type = self.kite.TRANSACTION_TYPE_SELL

            # Map order type
            if order_details['order_type'] == 'MARKET':
                ord_type = self.kite.ORDER_TYPE_MARKET
            else:
                ord_type = self.kite.ORDER_TYPE_LIMIT

            # Map product type
            if order_details['product'] == 'CNC':
                prod_type = self.kite.PRODUCT_CNC
            else:
                prod_type = self.kite.PRODUCT_MIS

            # Place the order
            order_id = self.kite.place_order(
                variety=self.kite.VARIETY_REGULAR,
                exchange=order_details['exchange'],
                tradingsymbol=order_details['symbol'],
                transaction_type=txn_type,
                quantity=order_details['quantity'],
                order_type=ord_type,
                product=prod_type
            )

            result = {
                **order_details,
                'order_id': order_id,
                'status': 'PLACED',
                'message': 'Order placed successfully via Kite API'
            }

            self.orders_placed.append(result)

            logger.info(f"[LIVE] {order_details['transaction_type']} order placed!")
            logger.info(f"  Symbol: {order_details['symbol']}")
            logger.info(f"  Quantity: {order_details['quantity']}")
            logger.info(f"  Order ID: {order_id}")
            logger.info(f"  Reason: {order_details['trigger_reason']}")

            return result

        except Exception as e:
            error_result = {
                **order_details,
                'order_id': None,
                'status': 'FAILED',
                'message': str(e)
            }

            logger.error(f"[LIVE] Order failed: {e}")
            return error_result

    def get_order_history(self) -> list:
        """Get list of all orders placed in this session"""
        return self.orders_placed

    def get_positions(self) -> Optional[list]:
        """Get current positions (live mode only)"""
        if self.simulation_mode or not self.kite:
            logger.warning("Positions only available in live mode")
            return None

        try:
            return self.kite.positions()
        except Exception as e:
            logger.error(f"Failed to get positions: {e}")
            return None

    def get_holdings(self) -> Optional[list]:
        """Get current holdings (live mode only)"""
        if self.simulation_mode or not self.kite:
            logger.warning("Holdings only available in live mode")
            return None

        try:
            return self.kite.holdings()
        except Exception as e:
            logger.error(f"Failed to get holdings: {e}")
            return None


def test_order_placement(simulation: bool = True):
    """
    Test order placement functionality

    Args:
        simulation: If True, run in simulation mode
    """
    print("\n" + "=" * 70)
    print("ORDER PLACEMENT TEST")
    print("=" * 70)
    print(f"Mode: {'SIMULATION' if simulation else 'LIVE'}")
    print("-" * 70)

    manager = OrderManager(simulation_mode=simulation)

    # Test BUY order triggered by CCI
    print("\n📈 Testing BUY order (CCI trigger)...")
    result1 = manager.place_order(
        symbol="RELIANCE",
        transaction_type="BUY",
        quantity=1,
        exchange="NSE",
        order_type="MARKET",
        product="CNC",
        trigger_reason="CCI crossed below -100"
    )
    print(f"Result: {result1['status']}")

    # Test SELL order triggered by CCI
    print("\n📉 Testing SELL order (CCI trigger)...")
    result2 = manager.place_order(
        symbol="RELIANCE",
        transaction_type="SELL",
        quantity=1,
        exchange="NSE",
        order_type="MARKET",
        product="CNC",
        trigger_reason="CCI crossed above +100"
    )
    print(f"Result: {result2['status']}")

    # Test BUY order triggered by Williams %R
    print("\n📈 Testing BUY order (Williams %R trigger)...")
    result3 = manager.place_order(
        symbol="INFY",
        transaction_type="BUY",
        quantity=1,
        exchange="NSE",
        order_type="MARKET",
        product="MIS",  # Intraday
        trigger_reason="Williams %R crossed below -80"
    )
    print(f"Result: {result3['status']}")

    # Test SELL order triggered by Williams %R
    print("\n📉 Testing SELL order (Williams %R trigger)...")
    result4 = manager.place_order(
        symbol="INFY",
        transaction_type="SELL",
        quantity=1,
        exchange="NSE",
        order_type="MARKET",
        product="MIS",
        trigger_reason="Williams %R crossed below -20"
    )
    print(f"Result: {result4['status']}")

    # Print summary
    print("\n" + "=" * 70)
    print("ORDER SUMMARY")
    print("=" * 70)

    orders = manager.get_order_history()
    for i, order in enumerate(orders, 1):
        print(f"\n{i}. {order['transaction_type']} {order['quantity']} {order['symbol']}")
        print(f"   Order ID: {order['order_id']}")
        print(f"   Status: {order['status']}")
        print(f"   Reason: {order['trigger_reason']}")

    return orders


if __name__ == "__main__":
    print("\nKite Order Placement Test")
    print("=" * 40)
    print("1. Simulation Mode (no real orders)")
    print("2. Live Mode (real orders via Kite API)")
    print()

    choice = input("Select mode (1 or 2, default=1): ").strip() or "1"

    if choice == "2":
        print("\n⚠️  WARNING: This will place REAL orders!")
        confirm = input("Type 'YES' to confirm: ").strip()
        if confirm == "YES":
            test_order_placement(simulation=False)
        else:
            print("Cancelled. Running in simulation mode instead.")
            test_order_placement(simulation=True)
    else:
        test_order_placement(simulation=True)

