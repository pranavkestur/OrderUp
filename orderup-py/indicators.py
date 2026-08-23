"""
Technical Indicators Module
Calculates CCI (Commodity Channel Index) and Williams %R indicators
"""

import pandas as pd
import numpy as np


def calculate_cci(df: pd.DataFrame, period: int = 20) -> pd.Series:
    """
    Calculate Commodity Channel Index (CCI)

    CCI = (Typical Price - SMA of TP) / (0.015 * Mean Deviation)
    Typical Price (TP) = (High + Low + Close) / 3

    Args:
        df: DataFrame with 'high', 'low', 'close' columns
        period: Lookback period for CCI calculation (default: 20)

    Returns:
        Series containing CCI values
    """
    # Calculate Typical Price
    typical_price = (df['high'] + df['low'] + df['close']) / 3

    # Calculate Simple Moving Average of Typical Price
    sma_tp = typical_price.rolling(window=period).mean()

    # Calculate Mean Deviation
    mean_deviation = typical_price.rolling(window=period).apply(
        lambda x: np.mean(np.abs(x - np.mean(x))), raw=True
    )

    # Calculate CCI
    cci = (typical_price - sma_tp) / (0.015 * mean_deviation)

    return cci


def calculate_williams_r(df: pd.DataFrame, period: int = 14) -> pd.Series:
    """
    Calculate Williams %R

    Williams %R = ((Highest High - Close) / (Highest High - Lowest Low)) * -100

    Args:
        df: DataFrame with 'high', 'low', 'close' columns
        period: Lookback period for Williams %R calculation (default: 14)

    Returns:
        Series containing Williams %R values
    """
    # Calculate Highest High over the period
    highest_high = df['high'].rolling(window=period).max()

    # Calculate Lowest Low over the period
    lowest_low = df['low'].rolling(window=period).min()

    # Calculate Williams %R
    williams_r = ((highest_high - df['close']) / (highest_high - lowest_low)) * -100

    return williams_r


def detect_cci_crossover(cci_values: pd.Series) -> dict:
    """
    Detect CCI crossover signals

    - SELL signal: CCI crosses above +100
    - BUY signal: CCI crosses below -100

    Args:
        cci_values: Series containing CCI values (at least 2 values needed)

    Returns:
        Dictionary with 'signal' and 'cci_value' keys
    """
    if len(cci_values) < 2:
        return {'signal': None, 'cci_value': None}

    current_cci = cci_values.iloc[-1]
    previous_cci = cci_values.iloc[-2]

    signal = None

    # CCI crossing above +100 -> SELL signal
    if previous_cci <= 100 and current_cci > 100:
        signal = 'SELL'

    # CCI crossing below -100 -> BUY signal
    elif previous_cci >= -100 and current_cci < -100:
        signal = 'BUY'

    return {
        'signal': signal,
        'cci_value': current_cci,
        'previous_cci': previous_cci
    }


def detect_williams_r_crossover(williams_r_values: pd.Series) -> dict:
    """
    Detect Williams %R crossover signals

    - SELL signal: Williams %R crosses below -20 (entering overbought)
    - BUY signal: Williams %R crosses below -80 (entering oversold)

    Args:
        williams_r_values: Series containing Williams %R values (at least 2 values needed)

    Returns:
        Dictionary with 'signal' and 'williams_r_value' keys
    """
    if len(williams_r_values) < 2:
        return {'signal': None, 'williams_r_value': None}

    current_wr = williams_r_values.iloc[-1]
    previous_wr = williams_r_values.iloc[-2]

    signal = None

    # Williams %R crossing below -20 -> SELL signal (overbought zone)
    if previous_wr >= -20 and current_wr < -20:
        signal = 'SELL'

    # Williams %R crossing below -80 -> BUY signal (oversold zone)
    elif previous_wr >= -80 and current_wr < -80:
        signal = 'BUY'

    return {
        'signal': signal,
        'williams_r_value': current_wr,
        'previous_williams_r': previous_wr
    }

