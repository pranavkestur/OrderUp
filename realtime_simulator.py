"""
Real-time Test Simulator
Simulates real-time market data to test indicator signals and order placement
"""

import time
import random
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from typing import Generator

from indicators import (
    calculate_cci,
    calculate_williams_r,
    detect_cci_crossover,
    detect_williams_r_crossover
)


class RealTimeSimulator:
    """
    Simulates real-time market data with controllable scenarios
    to test trading signals
    """

    def __init__(self, base_price: float = 2500.0, symbol: str = "RELIANCE"):
        self.base_price = base_price
        self.symbol = symbol
        self.current_price = base_price
        self.candles = []
        self.scenario = "random"  # random, bullish, bearish, cci_buy, cci_sell, wr_buy, wr_sell

    def set_scenario(self, scenario: str):
        """
        Set the price movement scenario

        Scenarios:
        - random: Random price movement
        - bullish: Trending up
        - bearish: Trending down
        - cci_buy: Force CCI to cross below -100 (BUY signal)
        - cci_sell: Force CCI to cross above +100 (SELL signal)
        - wr_buy: Force Williams %R to cross below -80 (BUY signal)
        - wr_sell: Force Williams %R to cross below -20 (SELL signal)
        """
        self.scenario = scenario
        print(f"Scenario set to: {scenario}")

    def generate_candle(self) -> dict:
        """Generate a single OHLC candle based on current scenario"""

        # Determine price movement based on scenario
        if self.scenario == "bullish":
            change = abs(random.gauss(0.5, 0.3))
        elif self.scenario == "bearish":
            change = -abs(random.gauss(0.5, 0.3))
        elif self.scenario == "cci_buy":
            # Push price down aggressively to trigger CCI < -100
            change = -abs(random.gauss(1.5, 0.5))
        elif self.scenario == "cci_sell":
            # Push price up aggressively to trigger CCI > +100
            change = abs(random.gauss(1.5, 0.5))
        elif self.scenario == "wr_buy":
            # Push price down to trigger Williams %R < -80
            change = -abs(random.gauss(1.0, 0.4))
        elif self.scenario == "wr_sell":
            # Push price up to trigger Williams %R > -20
            change = abs(random.gauss(1.0, 0.4))
        else:  # random
            change = random.gauss(0, 0.5)

        # Update price
        self.current_price *= (1 + change / 100)

        # Generate OHLC
        close = self.current_price
        volatility = self.current_price * 0.002  # 0.2% volatility

        high = close + abs(random.gauss(0, volatility))
        low = close - abs(random.gauss(0, volatility))
        open_price = close + random.gauss(0, volatility * 0.5)

        candle = {
            'date': datetime.now(),
            'open': round(open_price, 2),
            'high': round(max(high, open_price, close), 2),
            'low': round(min(low, open_price, close), 2),
            'close': round(close, 2),
            'volume': random.randint(10000, 100000)
        }

        self.candles.append(candle)

        # Keep only last 100 candles
        if len(self.candles) > 100:
            self.candles = self.candles[-100:]

        return candle

    def get_historical_data(self) -> pd.DataFrame:
        """Get historical candles as DataFrame for indicator calculation"""
        if not self.candles:
            # Generate initial historical data
            self._generate_initial_history()

        df = pd.DataFrame(self.candles)
        df.set_index('date', inplace=True)
        return df

    def _generate_initial_history(self, n_candles: int = 50):
        """Generate initial historical data for indicator calculation"""
        # Reset candles
        self.candles = []
        self.current_price = self.base_price

        start_time = datetime.now() - timedelta(minutes=5 * n_candles)

        for i in range(n_candles):
            change = random.gauss(0, 0.3)
            self.current_price *= (1 + change / 100)

            close = self.current_price
            volatility = self.current_price * 0.002

            high = close + abs(random.gauss(0, volatility))
            low = close - abs(random.gauss(0, volatility))
            open_price = close + random.gauss(0, volatility * 0.5)

            candle = {
                'date': start_time + timedelta(minutes=5 * i),
                'open': round(open_price, 2),
                'high': round(max(high, open_price, close), 2),
                'low': round(min(low, open_price, close), 2),
                'close': round(close, 2),
                'volume': random.randint(10000, 100000)
            }
            self.candles.append(candle)

    def stream_candles(self, interval_seconds: float = 2.0) -> Generator[dict, None, None]:
        """
        Generate a stream of candles at specified interval
        Simulates real-time data feed
        """
        # Generate initial history if needed
        if len(self.candles) < 30:
            self._generate_initial_history()

        while True:
            candle = self.generate_candle()
            yield candle
            time.sleep(interval_seconds)


def run_realtime_test(duration_seconds: int = 60, scenario: str = "random"):
    """
    Run a real-time simulation test

    Args:
        duration_seconds: How long to run the test
        scenario: Price movement scenario
    """
    print("=" * 70)
    print("REAL-TIME TRADING SIMULATION TEST")
    print("=" * 70)
    print(f"Duration: {duration_seconds} seconds")
    print(f"Scenario: {scenario}")
    print("-" * 70)

    simulator = RealTimeSimulator(base_price=2500.0, symbol="RELIANCE")
    simulator.set_scenario(scenario)

    # Generate initial history
    simulator._generate_initial_history(50)

    start_time = time.time()
    candle_count = 0
    signals_detected = []

    print("\nStarting real-time simulation...\n")
    print(f"{'Time':<12} {'Close':>10} {'CCI':>10} {'W%R':>10} {'CCI Signal':>12} {'W%R Signal':>12}")
    print("-" * 70)

    try:
        while (time.time() - start_time) < duration_seconds:
            # Generate new candle
            candle = simulator.generate_candle()
            candle_count += 1

            # Get DataFrame with all candles
            df = simulator.get_historical_data()

            # Calculate indicators
            df['cci'] = calculate_cci(df, period=20)
            df['williams_r'] = calculate_williams_r(df, period=14)

            # Get valid indicator values
            cci_values = df['cci'].dropna()
            wr_values = df['williams_r'].dropna()

            # Detect crossover signals
            cci_signal = detect_cci_crossover(cci_values)
            wr_signal = detect_williams_r_crossover(wr_values)

            # Current values
            current_cci = cci_values.iloc[-1] if len(cci_values) > 0 else 0
            current_wr = wr_values.iloc[-1] if len(wr_values) > 0 else 0

            # Print status
            cci_sig_str = cci_signal['signal'] if cci_signal['signal'] else "-"
            wr_sig_str = wr_signal['signal'] if wr_signal['signal'] else "-"

            timestamp = datetime.now().strftime("%H:%M:%S")
            print(f"{timestamp:<12} {candle['close']:>10.2f} {current_cci:>10.2f} {current_wr:>10.2f} {cci_sig_str:>12} {wr_sig_str:>12}")

            # Track signals
            if cci_signal['signal']:
                signals_detected.append({
                    'time': timestamp,
                    'type': 'CCI',
                    'signal': cci_signal['signal'],
                    'value': current_cci
                })
                print(f"  *** CCI {cci_signal['signal']} SIGNAL DETECTED! CCI crossed {'above +100' if cci_signal['signal'] == 'SELL' else 'below -100'}")

            if wr_signal['signal']:
                signals_detected.append({
                    'time': timestamp,
                    'type': 'WILLIAMS_R',
                    'signal': wr_signal['signal'],
                    'value': current_wr
                })
                print(f"  *** Williams %R {wr_signal['signal']} SIGNAL DETECTED! W%R crossed {'below -20' if wr_signal['signal'] == 'SELL' else 'below -80'}")

            # Wait before next candle
            time.sleep(1.5)

    except KeyboardInterrupt:
        print("\n\nTest interrupted by user")

    print("\n" + "=" * 70)
    print("SIMULATION RESULTS")
    print("=" * 70)
    print(f"Total candles generated: {candle_count}")
    print(f"Signals detected: {len(signals_detected)}")

    if signals_detected:
        print("\nSignal Details:")
        for sig in signals_detected:
            print(f"  [{sig['time']}] {sig['type']} -> {sig['signal']} (value: {sig['value']:.2f})")

    return signals_detected


if __name__ == "__main__":
    import sys

    print("\nReal-Time Test Scenarios:")
    print("1. random    - Random price movement")
    print("2. cci_buy   - Force CCI BUY signal (crosses below -100)")
    print("3. cci_sell  - Force CCI SELL signal (crosses above +100)")
    print("4. wr_buy    - Force Williams %R BUY signal (crosses below -80)")
    print("5. wr_sell   - Force Williams %R SELL signal (crosses below -20)")
    print()

    scenario = input("Select scenario (1-5, default=1): ").strip() or "1"

    scenario_map = {
        "1": "random",
        "2": "cci_buy",
        "3": "cci_sell",
        "4": "wr_buy",
        "5": "wr_sell"
    }

    selected = scenario_map.get(scenario, "random")

    duration = input("Test duration in seconds (default=30): ").strip() or "30"

    run_realtime_test(duration_seconds=int(duration), scenario=selected)

