"""
Kite Connect Trading Bot
Places buy/sell orders based on CCI and Williams %R indicator signals
"""

import os
import logging
from datetime import datetime, timedelta
from typing import Optional

import pandas as pd
from dotenv import load_dotenv
from kiteconnect import KiteConnect

from indicators import (
    calculate_cci,
    calculate_williams_r,
    detect_cci_crossover,
    detect_williams_r_crossover
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('trading_bot.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)


class KiteTradingBot:
    """
    Trading bot that places orders based on technical indicator signals
    """

    def __init__(self):
        """Initialize the trading bot with Kite Connect API"""
        load_dotenv()

        self.api_key = os.getenv('KITE_API_KEY')
        self.api_secret = os.getenv('KITE_API_SECRET')
        self.access_token = os.getenv('KITE_ACCESS_TOKEN')

        # Trading configuration
        self.stock_symbol = os.getenv('STOCK_SYMBOL', 'RELIANCE')
        self.exchange = os.getenv('EXCHANGE', 'NSE')
        self.quantity = int(os.getenv('QUANTITY', 1))

        # Initialize Kite Connect
        self.kite = KiteConnect(api_key=self.api_key)

        if self.access_token:
            self.kite.set_access_token(self.access_token)
            logger.info("Kite Connect initialized with existing access token")
        else:
            logger.warning("No access token found. Run authenticate() first.")

        # Track last signal to avoid duplicate orders
        self.last_cci_signal = None
        self.last_williams_r_signal = None

    def get_login_url(self) -> str:
        """
        Get the Kite Connect login URL for authentication

        Returns:
            Login URL string
        """
        login_url = self.kite.login_url()
        logger.info(f"Login URL: {login_url}")
        return login_url

    def authenticate(self, request_token: str) -> str:
        """
        Complete authentication with request token

        Args:
            request_token: Token received after Kite login

        Returns:
            Access token string
        """
        try:
            data = self.kite.generate_session(request_token, api_secret=self.api_secret)
            self.access_token = data['access_token']
            self.kite.set_access_token(self.access_token)
            logger.info("Authentication successful!")
            logger.info(f"Access Token: {self.access_token}")
            logger.info("Save this access token in your .env file for future use")
            return self.access_token
        except Exception as e:
            logger.error(f"Authentication failed: {e}")
            raise

    def get_instrument_token(self, symbol: str, exchange: str) -> Optional[int]:
        """
        Get instrument token for a given symbol and exchange

        Args:
            symbol: Stock symbol (e.g., 'RELIANCE')
            exchange: Exchange name (e.g., 'NSE')

        Returns:
            Instrument token or None if not found
        """
        try:
            instruments = self.kite.ltp(f"{exchange}:{symbol}")
            instrument_key = f"{exchange}:{symbol}"
            if instrument_key in instruments:
                return instruments[instrument_key]['instrument_token']
            return None
        except Exception as e:
            logger.error(f"Error getting instrument token: {e}")
            return None

    def fetch_historical_data(
        self,
        instrument_token: int,
        interval: str = "5minute",
        days: int = 5
    ) -> pd.DataFrame:
        """
        Fetch historical OHLC data for indicator calculation

        Args:
            instrument_token: Kite instrument token
            interval: Candle interval (5minute, 15minute, day, etc.)
            days: Number of days of historical data to fetch

        Returns:
            DataFrame with OHLC data
        """
        try:
            to_date = datetime.now()
            from_date = to_date - timedelta(days=days)

            historical_data = self.kite.historical_data(
                instrument_token,
                from_date,
                to_date,
                interval
            )

            df = pd.DataFrame(historical_data)

            if not df.empty:
                df.columns = ['date', 'open', 'high', 'low', 'close', 'volume']
                df['date'] = pd.to_datetime(df['date'])
                df.set_index('date', inplace=True)
                logger.info(f"Fetched {len(df)} candles of historical data")

            return df

        except Exception as e:
            logger.error(f"Error fetching historical data: {e}")
            return pd.DataFrame()

    def place_buy_order(
        self,
        symbol: str,
        quantity: int,
        exchange: str = "NSE",
        order_type: str = "MARKET",
        product: str = "CNC"
    ) -> Optional[str]:
        """
        Place a BUY order

        Args:
            symbol: Stock symbol
            quantity: Number of shares to buy
            exchange: Exchange (NSE/BSE)
            order_type: MARKET or LIMIT
            product: CNC (delivery) or MIS (intraday)

        Returns:
            Order ID or None if failed
        """
        try:
            order_id = self.kite.place_order(
                variety=self.kite.VARIETY_REGULAR,
                exchange=exchange,
                tradingsymbol=symbol,
                transaction_type=self.kite.TRANSACTION_TYPE_BUY,
                quantity=quantity,
                order_type=self.kite.ORDER_TYPE_MARKET if order_type == "MARKET" else self.kite.ORDER_TYPE_LIMIT,
                product=self.kite.PRODUCT_CNC if product == "CNC" else self.kite.PRODUCT_MIS
            )
            logger.info(f"BUY order placed successfully! Order ID: {order_id}")
            logger.info(f"  Symbol: {symbol}, Quantity: {quantity}, Exchange: {exchange}")
            return order_id
        except Exception as e:
            logger.error(f"Error placing BUY order: {e}")
            return None

    def place_sell_order(
        self,
        symbol: str,
        quantity: int,
        exchange: str = "NSE",
        order_type: str = "MARKET",
        product: str = "CNC"
    ) -> Optional[str]:
        """
        Place a SELL order

        Args:
            symbol: Stock symbol
            quantity: Number of shares to sell
            exchange: Exchange (NSE/BSE)
            order_type: MARKET or LIMIT
            product: CNC (delivery) or MIS (intraday)

        Returns:
            Order ID or None if failed
        """
        try:
            order_id = self.kite.place_order(
                variety=self.kite.VARIETY_REGULAR,
                exchange=exchange,
                tradingsymbol=symbol,
                transaction_type=self.kite.TRANSACTION_TYPE_SELL,
                quantity=quantity,
                order_type=self.kite.ORDER_TYPE_MARKET if order_type == "MARKET" else self.kite.ORDER_TYPE_LIMIT,
                product=self.kite.PRODUCT_CNC if product == "CNC" else self.kite.PRODUCT_MIS
            )
            logger.info(f"SELL order placed successfully! Order ID: {order_id}")
            logger.info(f"  Symbol: {symbol}, Quantity: {quantity}, Exchange: {exchange}")
            return order_id
        except Exception as e:
            logger.error(f"Error placing SELL order: {e}")
            return None

    def check_signals_and_trade(self) -> dict:
        """
        Check indicator signals and place orders if conditions are met

        Returns:
            Dictionary with signal information and order status
        """
        result = {
            'timestamp': datetime.now().isoformat(),
            'symbol': self.stock_symbol,
            'cci_signal': None,
            'williams_r_signal': None,
            'orders': []
        }

        # Get instrument token
        instruments = self.kite.instruments(self.exchange)
        instrument_token = None
        for instrument in instruments:
            if instrument['tradingsymbol'] == self.stock_symbol:
                instrument_token = instrument['instrument_token']
                break

        if not instrument_token:
            logger.error(f"Instrument not found: {self.stock_symbol}")
            result['error'] = "Instrument not found"
            return result

        # Fetch historical data (5-minute candles)
        df = self.fetch_historical_data(instrument_token, interval="5minute", days=5)

        if df.empty:
            logger.error("No historical data available")
            result['error'] = "No historical data"
            return result

        # Calculate indicators
        df['cci'] = calculate_cci(df, period=20)
        df['williams_r'] = calculate_williams_r(df, period=14)

        # Get the latest valid values (exclude NaN)
        cci_values = df['cci'].dropna()
        williams_r_values = df['williams_r'].dropna()

        # Check CCI crossover
        cci_signal = detect_cci_crossover(cci_values)
        result['cci_signal'] = cci_signal

        if cci_signal['signal'] and cci_signal['signal'] != self.last_cci_signal:
            logger.info(f"CCI Signal detected: {cci_signal['signal']}")
            logger.info(f"  Previous CCI: {cci_signal['previous_cci']:.2f}, Current CCI: {cci_signal['cci_value']:.2f}")

            if cci_signal['signal'] == 'BUY':
                order_id = self.place_buy_order(
                    symbol=self.stock_symbol,
                    quantity=self.quantity,
                    exchange=self.exchange
                )
                if order_id:
                    result['orders'].append({
                        'type': 'BUY',
                        'trigger': 'CCI',
                        'order_id': order_id
                    })

            elif cci_signal['signal'] == 'SELL':
                order_id = self.place_sell_order(
                    symbol=self.stock_symbol,
                    quantity=self.quantity,
                    exchange=self.exchange
                )
                if order_id:
                    result['orders'].append({
                        'type': 'SELL',
                        'trigger': 'CCI',
                        'order_id': order_id
                    })

            self.last_cci_signal = cci_signal['signal']

        # Check Williams %R crossover
        williams_r_signal = detect_williams_r_crossover(williams_r_values)
        result['williams_r_signal'] = williams_r_signal

        if williams_r_signal['signal'] and williams_r_signal['signal'] != self.last_williams_r_signal:
            logger.info(f"Williams %R Signal detected: {williams_r_signal['signal']}")
            logger.info(f"  Previous W%R: {williams_r_signal['previous_williams_r']:.2f}, Current W%R: {williams_r_signal['williams_r_value']:.2f}")

            if williams_r_signal['signal'] == 'BUY':
                order_id = self.place_buy_order(
                    symbol=self.stock_symbol,
                    quantity=self.quantity,
                    exchange=self.exchange
                )
                if order_id:
                    result['orders'].append({
                        'type': 'BUY',
                        'trigger': 'WILLIAMS_R',
                        'order_id': order_id
                    })

            elif williams_r_signal['signal'] == 'SELL':
                order_id = self.place_sell_order(
                    symbol=self.stock_symbol,
                    quantity=self.quantity,
                    exchange=self.exchange
                )
                if order_id:
                    result['orders'].append({
                        'type': 'SELL',
                        'trigger': 'WILLIAMS_R',
                        'order_id': order_id
                    })

            self.last_williams_r_signal = williams_r_signal['signal']

        return result

    def get_current_indicators(self) -> dict:
        """
        Get current indicator values without placing orders

        Returns:
            Dictionary with current CCI and Williams %R values
        """
        # Get instrument token
        instruments = self.kite.instruments(self.exchange)
        instrument_token = None
        for instrument in instruments:
            if instrument['tradingsymbol'] == self.stock_symbol:
                instrument_token = instrument['instrument_token']
                break

        if not instrument_token:
            return {'error': 'Instrument not found'}

        # Fetch historical data
        df = self.fetch_historical_data(instrument_token, interval="5minute", days=5)

        if df.empty:
            return {'error': 'No historical data'}

        # Calculate indicators
        df['cci'] = calculate_cci(df, period=20)
        df['williams_r'] = calculate_williams_r(df, period=14)

        latest = df.iloc[-1]

        return {
            'timestamp': str(df.index[-1]),
            'symbol': self.stock_symbol,
            'close': latest['close'],
            'cci': latest['cci'],
            'williams_r': latest['williams_r'],
            'cci_signal_zone': 'OVERBOUGHT' if latest['cci'] > 100 else ('OVERSOLD' if latest['cci'] < -100 else 'NEUTRAL'),
            'williams_r_signal_zone': 'OVERBOUGHT' if latest['williams_r'] > -20 else ('OVERSOLD' if latest['williams_r'] < -80 else 'NEUTRAL')
        }


def main():
    """Main entry point for the trading bot"""
    import schedule
    import time

    logger.info("=" * 60)
    logger.info("Starting Kite Trading Bot")
    logger.info("=" * 60)

    bot = KiteTradingBot()

    # Check if authentication is needed
    if not bot.access_token:
        print("\n" + "=" * 60)
        print("AUTHENTICATION REQUIRED")
        print("=" * 60)
        print(f"\n1. Visit this URL to login:\n   {bot.get_login_url()}")
        print("\n2. After login, you'll be redirected to your redirect URL")
        print("   with a 'request_token' parameter in the URL")
        print("\n3. Copy the request_token and enter it below:\n")

        request_token = input("Enter request_token: ").strip()
        if request_token:
            access_token = bot.authenticate(request_token)
            print(f"\nSave this in your .env file:")
            print(f"KITE_ACCESS_TOKEN={access_token}")
        else:
            print("No token provided. Exiting.")
            return

    # Initial check
    logger.info("Performing initial signal check...")
    result = bot.check_signals_and_trade()
    logger.info(f"Initial check result: {result}")

    # Schedule regular checks every 5 minutes (aligned with candle close)
    def scheduled_check():
        logger.info("-" * 40)
        logger.info("Running scheduled signal check...")
        result = bot.check_signals_and_trade()
        if result.get('orders'):
            logger.info(f"Orders placed: {result['orders']}")
        else:
            logger.info("No new signals detected")

    schedule.every(5).minutes.do(scheduled_check)

    logger.info("Bot started. Checking signals every 5 minutes...")
    logger.info("Press Ctrl+C to stop")

    try:
        while True:
            schedule.run_pending()
            time.sleep(1)
    except KeyboardInterrupt:
        logger.info("\nBot stopped by user")


if __name__ == "__main__":
    main()

