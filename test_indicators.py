"""
Test script to verify indicator calculations
Run this to test indicators without connecting to Kite API
"""

import pandas as pd
import numpy as np
from indicators import (
    calculate_cci,
    calculate_williams_r,
    detect_cci_crossover,
    detect_williams_r_crossover
)


def generate_sample_data(n_candles: int = 100) -> pd.DataFrame:
    """Generate sample OHLC data for testing"""
    np.random.seed(42)

    # Start with a base price
    base_price = 100.0
    prices = [base_price]

    # Generate random walk
    for _ in range(n_candles - 1):
        change = np.random.normal(0, 1)
        prices.append(prices[-1] + change)

    # Create OHLC from prices
    data = []
    for i, close in enumerate(prices):
        high = close + abs(np.random.normal(0, 0.5))
        low = close - abs(np.random.normal(0, 0.5))
        open_price = close + np.random.normal(0, 0.3)

        data.append({
            'date': pd.Timestamp('2024-01-01') + pd.Timedelta(minutes=5*i),
            'open': open_price,
            'high': max(high, open_price, close),
            'low': min(low, open_price, close),
            'close': close,
            'volume': np.random.randint(1000, 10000)
        })

    df = pd.DataFrame(data)
    df.set_index('date', inplace=True)
    return df


def test_cci():
    """Test CCI calculation and crossover detection"""
    print("=" * 60)
    print("Testing CCI (Commodity Channel Index)")
    print("=" * 60)

    df = generate_sample_data(100)
    df['cci'] = calculate_cci(df, period=20)

    print(f"\nSample CCI values (last 5 candles):")
    print(df[['close', 'cci']].tail())

    print(f"\nCCI Statistics:")
    print(f"  Min: {df['cci'].min():.2f}")
    print(f"  Max: {df['cci'].max():.2f}")
    print(f"  Mean: {df['cci'].mean():.2f}")

    # Test crossover detection
    cci_values = df['cci'].dropna()
    signal = detect_cci_crossover(cci_values)
    print(f"\nCCI Crossover Signal: {signal}")

    # Simulate a crossover scenario
    print("\n--- Simulating CCI Crossover Scenarios ---")

    # Crossing above +100 (SELL signal)
    test_cci_above = pd.Series([95, 98, 102])
    signal_above = detect_cci_crossover(test_cci_above)
    print(f"CCI [95 -> 98 -> 102] (crossing +100): {signal_above}")

    # Crossing below -100 (BUY signal)
    test_cci_below = pd.Series([-95, -98, -102])
    signal_below = detect_cci_crossover(test_cci_below)
    print(f"CCI [-95 -> -98 -> -102] (crossing -100): {signal_below}")


def test_williams_r():
    """Test Williams %R calculation and crossover detection"""
    print("\n" + "=" * 60)
    print("Testing Williams %R")
    print("=" * 60)

    df = generate_sample_data(100)
    df['williams_r'] = calculate_williams_r(df, period=14)

    print(f"\nSample Williams %R values (last 5 candles):")
    print(df[['close', 'williams_r']].tail())

    print(f"\nWilliams %R Statistics:")
    print(f"  Min: {df['williams_r'].min():.2f}")
    print(f"  Max: {df['williams_r'].max():.2f}")
    print(f"  Mean: {df['williams_r'].mean():.2f}")

    # Test crossover detection
    wr_values = df['williams_r'].dropna()
    signal = detect_williams_r_crossover(wr_values)
    print(f"\nWilliams %R Crossover Signal: {signal}")

    # Simulate crossover scenarios
    print("\n--- Simulating Williams %R Crossover Scenarios ---")

    # Crossing below -20 (SELL signal - entering overbought from above)
    test_wr_sell = pd.Series([-15, -18, -22])
    signal_sell = detect_williams_r_crossover(test_wr_sell)
    print(f"Williams %R [-15 -> -18 -> -22] (crossing -20): {signal_sell}")

    # Crossing below -80 (BUY signal - entering oversold)
    test_wr_buy = pd.Series([-75, -78, -82])
    signal_buy = detect_williams_r_crossover(test_wr_buy)
    print(f"Williams %R [-75 -> -78 -> -82] (crossing -80): {signal_buy}")


def test_combined_signals():
    """Test combined indicator signals"""
    print("\n" + "=" * 60)
    print("Testing Combined Signals")
    print("=" * 60)

    df = generate_sample_data(100)
    df['cci'] = calculate_cci(df, period=20)
    df['williams_r'] = calculate_williams_r(df, period=14)

    print("\nLast 10 rows with all indicators:")
    print(df[['close', 'cci', 'williams_r']].tail(10).to_string())

    # Check current zones
    latest = df.iloc[-1]
    print(f"\nCurrent Status:")
    print(f"  Close Price: {latest['close']:.2f}")
    print(f"  CCI: {latest['cci']:.2f}", end="")
    if latest['cci'] > 100:
        print(" (OVERBOUGHT)")
    elif latest['cci'] < -100:
        print(" (OVERSOLD)")
    else:
        print(" (NEUTRAL)")

    print(f"  Williams %R: {latest['williams_r']:.2f}", end="")
    if latest['williams_r'] > -20:
        print(" (OVERBOUGHT)")
    elif latest['williams_r'] < -80:
        print(" (OVERSOLD)")
    else:
        print(" (NEUTRAL)")


if __name__ == "__main__":
    test_cci()
    test_williams_r()
    test_combined_signals()

    print("\n" + "=" * 60)
    print("All tests completed!")
    print("=" * 60)

