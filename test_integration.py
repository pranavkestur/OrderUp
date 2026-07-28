"""
Full Integration Test
Combines real-time data simulation with order placement
Tests the complete trading workflow end-to-end
"""

import time
import logging
from datetime import datetime

from realtime_simulator import RealTimeSimulator
from test_orders import OrderManager
from indicators import (
    calculate_cci,
    calculate_williams_r,
    detect_cci_crossover,
    detect_williams_r_crossover
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class IntegrationTest:
    """
    Full integration test combining:
    - Real-time data simulation
    - Indicator calculation
    - Signal detection
    - Order placement
    """

    def __init__(self, symbol: str = "RELIANCE", simulation_mode: bool = True):
        self.symbol = symbol
        self.simulator = RealTimeSimulator(base_price=2500.0, symbol=symbol)
        self.order_manager = OrderManager(simulation_mode=simulation_mode)

        # Track signals to avoid duplicate orders
        self.last_cci_signal = None
        self.last_wr_signal = None

        # Statistics
        self.stats = {
            'candles_processed': 0,
            'cci_signals': 0,
            'wr_signals': 0,
            'orders_placed': 0,
            'start_time': None,
            'end_time': None
        }

    def run_test(self, duration_seconds: int = 60, scenario: str = "random"):
        """
        Run the full integration test

        Args:
            duration_seconds: Test duration
            scenario: Price scenario (random, cci_buy, cci_sell, wr_buy, wr_sell)
        """
        print("\n" + "=" * 80)
        print("🚀 FULL INTEGRATION TEST - OrderUp Trading Bot")
        print("=" * 80)
        print(f"Symbol: {self.symbol}")
        print(f"Duration: {duration_seconds} seconds")
        print(f"Scenario: {scenario}")
        print(f"Mode: {'SIMULATION' if self.order_manager.simulation_mode else 'LIVE'}")
        print("-" * 80)

        self.simulator.set_scenario(scenario)
        self.simulator._generate_initial_history(50)

        self.stats['start_time'] = datetime.now()

        print(f"\n{'Time':<10} {'Close':>10} {'CCI':>10} {'W%R':>10} {'Action':>20}")
        print("-" * 80)

        start_time = time.time()

        try:
            while (time.time() - start_time) < duration_seconds:
                self._process_candle()
                time.sleep(1)  # 1 second between candles for faster testing

        except KeyboardInterrupt:
            print("\n\n⚠️  Test interrupted by user")

        self.stats['end_time'] = datetime.now()
        self._print_summary()

        return self.stats

    def _process_candle(self):
        """Process a single candle and check for signals"""

        # Generate new candle
        candle = self.simulator.generate_candle()
        self.stats['candles_processed'] += 1

        # Get all historical data
        df = self.simulator.get_historical_data()

        # Calculate indicators
        df['cci'] = calculate_cci(df, period=20)
        df['williams_r'] = calculate_williams_r(df, period=14)

        # Get valid values
        cci_values = df['cci'].dropna()
        wr_values = df['williams_r'].dropna()

        if len(cci_values) < 2 or len(wr_values) < 2:
            return

        current_cci = cci_values.iloc[-1]
        current_wr = wr_values.iloc[-1]

        # Detect signals
        cci_signal = detect_cci_crossover(cci_values)
        wr_signal = detect_williams_r_crossover(wr_values)

        # Prepare display
        timestamp = datetime.now().strftime("%H:%M:%S")
        action = "-"

        # Process CCI signal
        if cci_signal['signal'] and cci_signal['signal'] != self.last_cci_signal:
            self.stats['cci_signals'] += 1
            self.last_cci_signal = cci_signal['signal']

            if cci_signal['signal'] == 'BUY':
                action = "🟢 CCI BUY"
                self._place_order("BUY", f"CCI crossed below -100 (value: {current_cci:.2f})")
            else:
                action = "🔴 CCI SELL"
                self._place_order("SELL", f"CCI crossed above +100 (value: {current_cci:.2f})")

        # Process Williams %R signal
        if wr_signal['signal'] and wr_signal['signal'] != self.last_wr_signal:
            self.stats['wr_signals'] += 1
            self.last_wr_signal = wr_signal['signal']

            if wr_signal['signal'] == 'BUY':
                action = "🟢 W%R BUY" if action == "-" else f"{action} + 🟢 W%R BUY"
                self._place_order("BUY", f"Williams %R crossed below -80 (value: {current_wr:.2f})")
            else:
                action = "🔴 W%R SELL" if action == "-" else f"{action} + 🔴 W%R SELL"
                self._place_order("SELL", f"Williams %R crossed below -20 (value: {current_wr:.2f})")

        # Print status line
        print(f"{timestamp:<10} {candle['close']:>10.2f} {current_cci:>10.2f} {current_wr:>10.2f} {action:>20}")

    def _place_order(self, transaction_type: str, reason: str):
        """Place an order"""
        result = self.order_manager.place_order(
            symbol=self.symbol,
            transaction_type=transaction_type,
            quantity=1,
            exchange="NSE",
            order_type="MARKET",
            product="CNC",
            trigger_reason=reason
        )

        if result['status'] in ['COMPLETE', 'PLACED']:
            self.stats['orders_placed'] += 1

    def _print_summary(self):
        """Print test summary"""
        duration = (self.stats['end_time'] - self.stats['start_time']).total_seconds()

        print("\n" + "=" * 80)
        print("📊 TEST SUMMARY")
        print("=" * 80)
        print(f"Duration: {duration:.1f} seconds")
        print(f"Candles processed: {self.stats['candles_processed']}")
        print(f"CCI signals detected: {self.stats['cci_signals']}")
        print(f"Williams %R signals detected: {self.stats['wr_signals']}")
        print(f"Orders placed: {self.stats['orders_placed']}")

        orders = self.order_manager.get_order_history()
        if orders:
            print("\n" + "-" * 80)
            print("📝 ORDER DETAILS")
            print("-" * 80)
            for i, order in enumerate(orders, 1):
                emoji = "🟢" if order['transaction_type'] == 'BUY' else "🔴"
                print(f"\n{i}. {emoji} {order['transaction_type']} {order['quantity']} {order['symbol']}")
                print(f"   Order ID: {order['order_id']}")
                print(f"   Status: {order['status']}")
                print(f"   Reason: {order['trigger_reason']}")
                print(f"   Time: {order['timestamp']}")

        print("\n" + "=" * 80)
        print("✅ Integration test completed!")
        print("=" * 80)


def main():
    """Main entry point for integration test"""
    print("\n" + "=" * 60)
    print("OrderUp - Full Integration Test")
    print("=" * 60)

    print("\nScenarios:")
    print("1. random    - Random price movement (may or may not trigger signals)")
    print("2. cci_buy   - Force CCI BUY signal")
    print("3. cci_sell  - Force CCI SELL signal")
    print("4. wr_buy    - Force Williams %R BUY signal")
    print("5. wr_sell   - Force Williams %R SELL signal")
    print("6. all       - Run all scenarios sequentially")
    print()

    choice = input("Select scenario (1-6, default=2): ").strip() or "2"

    scenario_map = {
        "1": "random",
        "2": "cci_buy",
        "3": "cci_sell",
        "4": "wr_buy",
        "5": "wr_sell"
    }

    print("\nModes:")
    print("1. Simulation (default) - No real orders")
    print("2. Live - Real orders via Kite API")
    mode = input("Select mode (1-2, default=1): ").strip() or "1"

    simulation_mode = (mode != "2")

    if not simulation_mode:
        print("\n⚠️  WARNING: Live mode will place REAL orders!")
        confirm = input("Type 'LIVE' to confirm: ").strip()
        if confirm != "LIVE":
            print("Cancelled. Using simulation mode.")
            simulation_mode = True

    duration = int(input("\nTest duration in seconds (default=30): ").strip() or "30")

    if choice == "6":
        # Run all scenarios
        for key, scenario in scenario_map.items():
            print(f"\n\n{'#' * 80}")
            print(f"Running scenario: {scenario}")
            print(f"{'#' * 80}")

            test = IntegrationTest(symbol="RELIANCE", simulation_mode=simulation_mode)
            test.run_test(duration_seconds=duration, scenario=scenario)

            if key != "5":  # Don't wait after last scenario
                print("\nNext scenario in 3 seconds...")
                time.sleep(3)
    else:
        scenario = scenario_map.get(choice, "cci_buy")
        test = IntegrationTest(symbol="RELIANCE", simulation_mode=simulation_mode)
        test.run_test(duration_seconds=duration, scenario=scenario)


if __name__ == "__main__":
    main()

