"""
OrderUp - Automated Trading Bot
Monitors CCI and Williams %R indicators and places orders automatically
"""

import os
import sys
import socket
import logging
import webbrowser
import threading
import time
from datetime import datetime, date
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from dotenv import load_dotenv, set_key

# Force IPv4
original_getaddrinfo = socket.getaddrinfo
def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses
socket.getaddrinfo = forced_ipv4_getaddrinfo

from kiteconnect import KiteConnect

from indicators import (
    calculate_cci,
    calculate_williams_r,
    detect_cci_crossover,
    detect_williams_r_crossover
)
from realtime_simulator import RealTimeSimulator

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('orderup.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Global variable to capture request token from callback
captured_request_token = None
server_should_stop = False


class CallbackHandler(BaseHTTPRequestHandler):
    """HTTP handler to capture OAuth callback"""

    def do_GET(self):
        global captured_request_token, server_should_stop

        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if 'request_token' in params:
            captured_request_token = params['request_token'][0]

            # Send success response
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()

            html = """
            <html>
            <head><title>OrderUp - Login Successful</title></head>
            <body style="font-family: Arial; text-align: center; padding: 50px;">
                <h1>✅ Login Successful!</h1>
                <p>You can close this window and return to the terminal.</p>
                <p>OrderUp is now running...</p>
            </body>
            </html>
            """
            self.wfile.write(html.encode())
            server_should_stop = True
        else:
            self.send_response(400)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # Suppress HTTP logs


class OrderUpBot:
    """Main trading bot class"""

    def __init__(self, test_mode: bool = False):
        load_dotenv()

        self.api_key = os.getenv('KITE_API_KEY')
        self.api_secret = os.getenv('KITE_API_SECRET')
        self.access_token = os.getenv('KITE_ACCESS_TOKEN')
        self.token_date = os.getenv('KITE_TOKEN_DATE')

        self.symbol = os.getenv('STOCK_SYMBOL', 'RELIANCE')
        self.exchange = os.getenv('EXCHANGE', 'NSE')
        self.quantity = int(os.getenv('QUANTITY', 1))
        self.fallback_price = float(os.getenv('FALLBACK_PRICE', 3000))

        self.kite = None
        self.running = False
        self.test_mode = test_mode  # For testing without real orders

        # Track signals to avoid duplicate orders
        self.last_cci_signal = None
        self.last_wr_signal = None

        # For simulation (since Personal plan doesn't have historical data)
        self.simulator = RealTimeSimulator(base_price=self.fallback_price, symbol=self.symbol)
        self.use_simulation = True  # Set to False if you have Connect plan with historical data

        self.env_path = os.path.join(os.path.dirname(__file__), '.env')

    def is_token_valid(self) -> bool:
        """Check if access token is valid for today"""
        if not self.access_token or not self.token_date:
            return False

        try:
            token_date = datetime.strptime(self.token_date, '%Y-%m-%d').date()
            return token_date == date.today()
        except:
            return False

    def save_token(self, access_token: str):
        """Save access token to .env file"""
        self.access_token = access_token
        self.token_date = date.today().strftime('%Y-%m-%d')

        set_key(self.env_path, 'KITE_ACCESS_TOKEN', access_token)
        set_key(self.env_path, 'KITE_TOKEN_DATE', self.token_date)

        logger.info("Access token saved to .env")

    def auto_login(self) -> bool:
        """Automated login flow with local callback server"""
        global captured_request_token, server_should_stop

        captured_request_token = None
        server_should_stop = False

        self.kite = KiteConnect(api_key=self.api_key)

        # Start local server to catch callback
        port = 5000

        # Allow port reuse to avoid "Address already in use" errors
        try:
            server = HTTPServer(('127.0.0.1', port), CallbackHandler)
            server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.timeout = 1
        except OSError as e:
            if "Address already in use" in str(e):
                logger.warning(f"Port {port} in use, killing existing process...")
                os.system(f"lsof -ti:{port} | xargs kill -9 2>/dev/null")
                time.sleep(1)
                server = HTTPServer(('127.0.0.1', port), CallbackHandler)
                server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server.timeout = 1
            else:
                raise

        server_thread = threading.Thread(target=self._run_server, args=(server,))
        server_thread.daemon = True
        server_thread.start()

        # Open browser for login
        login_url = self.kite.login_url()
        logger.info("Opening browser for Zerodha login...")
        print(f"\n🌐 Opening browser for login...")
        print(f"   If browser doesn't open, visit: {login_url}\n")
        webbrowser.open(login_url)

        # Wait for callback (max 2 minutes)
        timeout = 120
        start = time.time()

        while not captured_request_token and (time.time() - start) < timeout:
            time.sleep(0.5)

        # Force stop server
        server_should_stop = True
        try:
            server.shutdown()
        except:
            pass

        if not captured_request_token:
            logger.error("Login timeout - no callback received")
            return False

        # Generate session
        try:
            data = self.kite.generate_session(captured_request_token, api_secret=self.api_secret)
            access_token = data['access_token']
            self.kite.set_access_token(access_token)
            self.save_token(access_token)

            logger.info("✅ Login successful!")
            return True

        except Exception as e:
            logger.error(f"Failed to generate session: {e}")
            return False

    def _run_server(self, server):
        """Run HTTP server until stopped"""
        global server_should_stop
        while not server_should_stop:
            server.handle_request()

    def connect(self) -> bool:
        """Connect to Kite API, auto-login if needed"""
        print("\n" + "=" * 60)
        print("🚀 OrderUp Trading Bot")
        print("=" * 60)

        if self.is_token_valid():
            logger.info("Using saved access token from today")
            self.kite = KiteConnect(api_key=self.api_key)
            self.kite.set_access_token(self.access_token)

            # Verify token works
            try:
                profile = self.kite.profile()
                logger.info(f"Connected as: {profile['user_name']} ({profile['user_id']})")
                return True
            except:
                logger.warning("Saved token invalid, need to re-login")

        # Need fresh login
        return self.auto_login()

    def place_buy_order(self, reason: str) -> bool:
        """Place a BUY order"""
        if self.test_mode:
            logger.info(f"🟢 [TEST MODE] BUY ORDER SIMULATED!")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Reason: {reason}")
            return True

        try:
            order_id = self.kite.place_order(
                variety=self.kite.VARIETY_REGULAR,
                exchange=self.exchange,
                tradingsymbol=self.symbol,
                transaction_type=self.kite.TRANSACTION_TYPE_BUY,
                quantity=self.quantity,
                order_type=self.kite.ORDER_TYPE_MARKET,
                product=self.kite.PRODUCT_CNC
            )

            logger.info(f"🟢 BUY ORDER PLACED! ID: {order_id}")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Reason: {reason}")
            return True

        except Exception as e:
            error_msg = str(e)
            if "Markets are closed" in error_msg:
                logger.info("⏰ Markets closed. Placing GTT order instead...")
                return self.place_gtt_buy_order(reason)
            else:
                logger.error(f"❌ BUY ORDER FAILED: {e}")
                return False

    def place_sell_order(self, reason: str) -> bool:
        """Place a SELL order"""
        if self.test_mode:
            logger.info(f"🔴 [TEST MODE] SELL ORDER SIMULATED!")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Reason: {reason}")
            return True

        try:
            order_id = self.kite.place_order(
                variety=self.kite.VARIETY_REGULAR,
                exchange=self.exchange,
                tradingsymbol=self.symbol,
                transaction_type=self.kite.TRANSACTION_TYPE_SELL,
                quantity=self.quantity,
                order_type=self.kite.ORDER_TYPE_MARKET,
                product=self.kite.PRODUCT_CNC
            )

            logger.info(f"🔴 SELL ORDER PLACED! ID: {order_id}")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Reason: {reason}")
            return True

        except Exception as e:
            error_msg = str(e)
            if "Markets are closed" in error_msg:
                logger.info("⏰ Markets closed. Placing GTT order instead...")
                return self.place_gtt_sell_order(reason)
            else:
                logger.error(f"❌ SELL ORDER FAILED: {e}")
                return False

    def place_gtt_buy_order(self, reason: str) -> bool:
        """Place a GTT BUY order (works when markets are closed)"""
        try:
            # Use fallback price from config (Personal plan can't fetch LTP)
            last_price = self.fallback_price

            # Set trigger price slightly below current (to trigger on dip)
            trigger_price = round(last_price * 0.99, 1)  # 1% below
            limit_price = round(last_price * 1.01, 1)    # 1% above for limit

            gtt_id = self.kite.place_gtt(
                trigger_type=self.kite.GTT_TYPE_SINGLE,
                tradingsymbol=self.symbol,
                exchange=self.exchange,
                trigger_values=[trigger_price],
                last_price=last_price,
                orders=[{
                    "transaction_type": self.kite.TRANSACTION_TYPE_BUY,
                    "quantity": self.quantity,
                    "order_type": self.kite.ORDER_TYPE_LIMIT,
                    "product": self.kite.PRODUCT_CNC,
                    "price": limit_price
                }]
            )

            logger.info(f"🟢 GTT BUY ORDER PLACED! ID: {gtt_id}")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Trigger: ₹{trigger_price}, Limit: ₹{limit_price}")
            logger.info(f"   Reason: {reason}")
            return True

        except Exception as e:
            logger.error(f"❌ GTT BUY ORDER FAILED: {e}")
            return False

    def place_gtt_sell_order(self, reason: str) -> bool:
        """Place a GTT SELL order (works when markets are closed)"""
        try:
            # Use fallback price from config (Personal plan can't fetch LTP)
            last_price = self.fallback_price

            # Set trigger price slightly above current (to trigger on rise)
            trigger_price = round(last_price * 1.01, 1)  # 1% above
            limit_price = round(last_price * 0.99, 1)    # 1% below for limit

            gtt_id = self.kite.place_gtt(
                trigger_type=self.kite.GTT_TYPE_SINGLE,
                tradingsymbol=self.symbol,
                exchange=self.exchange,
                trigger_values=[trigger_price],
                last_price=last_price,
                orders=[{
                    "transaction_type": self.kite.TRANSACTION_TYPE_SELL,
                    "quantity": self.quantity,
                    "order_type": self.kite.ORDER_TYPE_LIMIT,
                    "product": self.kite.PRODUCT_CNC,
                    "price": limit_price
                }]
            )

            logger.info(f"🔴 GTT SELL ORDER PLACED! ID: {gtt_id}")
            logger.info(f"   Symbol: {self.symbol}, Qty: {self.quantity}")
            logger.info(f"   Trigger: ₹{trigger_price}, Limit: ₹{limit_price}")
            logger.info(f"   Reason: {reason}")
            return True

        except Exception as e:
            logger.error(f"❌ GTT SELL ORDER FAILED: {e}")
            return False

    def check_signals(self):
        """Check indicators and place orders if conditions met"""

        # Get market data (using simulator for Personal plan)
        if self.use_simulation:
            self.simulator.generate_candle()
            df = self.simulator.get_historical_data()
        else:
            # TODO: Implement real historical data fetch for Connect plan
            pass

        if df.empty or len(df) < 25:
            return

        # Calculate indicators
        df['cci'] = calculate_cci(df, period=20)
        df['williams_r'] = calculate_williams_r(df, period=14)

        cci_values = df['cci'].dropna()
        wr_values = df['williams_r'].dropna()

        if len(cci_values) < 2 or len(wr_values) < 2:
            return

        current_cci = cci_values.iloc[-1]
        current_wr = wr_values.iloc[-1]
        current_price = df['close'].iloc[-1]

        # Check CCI crossover
        cci_signal = detect_cci_crossover(cci_values)

        if cci_signal['signal'] and cci_signal['signal'] != self.last_cci_signal:
            self.last_cci_signal = cci_signal['signal']

            if cci_signal['signal'] == 'BUY':
                logger.info(f"📊 CCI crossed below -100 (value: {current_cci:.2f})")
                self.place_buy_order(f"CCI crossed below -100 ({current_cci:.2f})")
            else:
                logger.info(f"📊 CCI crossed above +100 (value: {current_cci:.2f})")
                self.place_sell_order(f"CCI crossed above +100 ({current_cci:.2f})")

        # Check Williams %R crossover
        wr_signal = detect_williams_r_crossover(wr_values)

        if wr_signal['signal'] and wr_signal['signal'] != self.last_wr_signal:
            self.last_wr_signal = wr_signal['signal']

            if wr_signal['signal'] == 'BUY':
                logger.info(f"📊 Williams %R crossed below -80 (value: {current_wr:.2f})")
                self.place_buy_order(f"Williams %R crossed below -80 ({current_wr:.2f})")
            else:
                logger.info(f"📊 Williams %R crossed below -20 (value: {current_wr:.2f})")
                self.place_sell_order(f"Williams %R crossed below -20 ({current_wr:.2f})")

        # Log current status
        logger.debug(f"Price: {current_price:.2f} | CCI: {current_cci:.2f} | W%R: {current_wr:.2f}")

    def run(self, force_test_signals: bool = False):
        """Main loop - runs continuously"""
        if not self.test_mode:
            if not self.connect():
                logger.error("Failed to connect. Exiting.")
                return
        else:
            print("\n" + "=" * 60)
            print("🧪 OrderUp Trading Bot - TEST MODE")
            print("=" * 60)
            logger.info("Running in TEST MODE - no real orders will be placed")

        self.running = True
        check_interval = 5 if (self.test_mode or force_test_signals) else 300  # 5 sec for testing, 5 min for production

        print("\n" + "-" * 60)
        print(f"📈 Monitoring {self.symbol} on {self.exchange}")
        print(f"📊 Indicators: CCI (20), Williams %R (14)")
        print(f"⏱️  Check interval: {check_interval} seconds")
        if self.test_mode:
            print(f"🧪 TEST MODE: Orders will be simulated, not real")
        if force_test_signals:
            print(f"⚡ FORCE SIGNALS: Will trigger test signals")
        print(f"🛑 Press Ctrl+C to stop")
        print("-" * 60 + "\n")

        # Initialize simulator with history
        if self.use_simulation:
            self.simulator._generate_initial_history(50)

        # Force test signals if requested
        if force_test_signals:
            self._run_test_signals()
            return

        logger.info("Bot started. Monitoring for signals...")

        try:
            while self.running:
                try:
                    self.check_signals()
                except Exception as e:
                    logger.error(f"Error checking signals: {e}")

                # Wait for next check
                time.sleep(check_interval)

        except KeyboardInterrupt:
            print("\n")
            logger.info("🛑 Bot stopped by user")
            self.running = False

        print("\n" + "=" * 60)
        print("OrderUp Bot stopped. Goodbye!")
        print("=" * 60)

    def _run_test_signals(self):
        """Run through test signals to verify the flow works"""
        print("\n" + "=" * 60)
        print("🧪 RUNNING TEST SIGNALS")
        print("=" * 60 + "\n")

        test_cases = [
            ("CCI BUY", "BUY", "CCI crossed below -100 (-105.50)"),
            ("CCI SELL", "SELL", "CCI crossed above +100 (+108.25)"),
            ("Williams %R BUY", "BUY", "Williams %R crossed below -80 (-82.30)"),
            ("Williams %R SELL", "SELL", "Williams %R crossed below -20 (-18.75)"),
        ]

        for i, (name, order_type, reason) in enumerate(test_cases, 1):
            print(f"\n--- Test {i}/4: {name} ---")
            logger.info(f"📊 Signal detected: {name}")

            if order_type == "BUY":
                self.place_buy_order(reason)
            else:
                self.place_sell_order(reason)

            time.sleep(1)  # Brief pause between tests

        print("\n" + "=" * 60)
        print("✅ ALL TEST SIGNALS COMPLETED!")
        print("=" * 60)
        print("\nThe bot successfully processed all 4 signal types.")
        print("When markets are open, real orders will be placed.\n")

    def run_live_test(self):
        """
        Test the live workflow with real API connection.
        Uses simulated market data but places REAL orders (or GTT if markets closed).
        Runs with fast 10-second intervals to quickly test the full flow.
        """
        if not self.connect():
            logger.error("Failed to connect. Exiting.")
            return

        print("\n" + "=" * 60)
        print("🔴 LIVE WORKFLOW TEST")
        print("=" * 60)
        print(f"\n⚠️  WARNING: This will place REAL orders!")
        print(f"   • Symbol: {self.symbol}")
        print(f"   • Quantity: {self.quantity}")
        print(f"   • If markets are closed, GTT orders will be placed")
        print()

        confirm = input("Type 'LIVE' to continue, or anything else to abort: ").strip()
        if confirm != "LIVE":
            print("Aborted.")
            return

        self.running = True
        check_interval = 10  # Fast checks for testing

        print("\n" + "-" * 60)
        print(f"📈 LIVE TEST: Monitoring {self.symbol} on {self.exchange}")
        print(f"📊 Indicators: CCI (20), Williams %R (14)")
        print(f"⏱️  Check interval: {check_interval} seconds (fast for testing)")
        print(f"🛑 Press Ctrl+C to stop")
        print("-" * 60 + "\n")

        # Initialize simulator with history
        if self.use_simulation:
            self.simulator._generate_initial_history(50)

        # Choose a scenario to force a signal quickly
        print("Select a scenario to test:")
        print("1. Random price movement (may take time for signals)")
        print("2. Force CCI BUY signal (price drops aggressively)")
        print("3. Force CCI SELL signal (price rises aggressively)")
        print("4. Force Williams %R BUY signal")
        print("5. Force Williams %R SELL signal")

        choice = input("\nSelect (1-5, default=1): ").strip() or "1"

        scenarios = {
            "1": "random",
            "2": "cci_buy",
            "3": "cci_sell",
            "4": "wr_buy",
            "5": "wr_sell"
        }
        self.simulator.set_scenario(scenarios.get(choice, "random"))

        logger.info("Live test started. Monitoring for signals...")
        signals_triggered = 0
        max_signals = 2  # Stop after 2 signals for safety

        try:
            while self.running and signals_triggered < max_signals:
                try:
                    # Generate new candle
                    self.simulator.generate_candle()
                    df = self.simulator.get_historical_data()

                    if df.empty or len(df) < 25:
                        time.sleep(check_interval)
                        continue

                    # Calculate indicators
                    df['cci'] = calculate_cci(df, period=20)
                    df['williams_r'] = calculate_williams_r(df, period=14)

                    cci_values = df['cci'].dropna()
                    wr_values = df['williams_r'].dropna()

                    if len(cci_values) < 2 or len(wr_values) < 2:
                        time.sleep(check_interval)
                        continue

                    current_cci = cci_values.iloc[-1]
                    current_wr = wr_values.iloc[-1]
                    current_price = df['close'].iloc[-1]

                    # Log current status
                    timestamp = datetime.now().strftime("%H:%M:%S")
                    print(f"[{timestamp}] Price: ₹{current_price:.2f} | CCI: {current_cci:.2f} | W%R: {current_wr:.2f}")

                    # Check CCI crossover
                    cci_signal = detect_cci_crossover(cci_values)

                    if cci_signal['signal'] and cci_signal['signal'] != self.last_cci_signal:
                        self.last_cci_signal = cci_signal['signal']
                        signals_triggered += 1

                        if cci_signal['signal'] == 'BUY':
                            logger.info(f"📊 CCI crossed below -100 (value: {current_cci:.2f})")
                            self.place_buy_order(f"CCI crossed below -100 ({current_cci:.2f})")
                        else:
                            logger.info(f"📊 CCI crossed above +100 (value: {current_cci:.2f})")
                            self.place_sell_order(f"CCI crossed above +100 ({current_cci:.2f})")

                    # Check Williams %R crossover
                    wr_signal = detect_williams_r_crossover(wr_values)

                    if wr_signal['signal'] and wr_signal['signal'] != self.last_wr_signal:
                        self.last_wr_signal = wr_signal['signal']
                        signals_triggered += 1

                        if wr_signal['signal'] == 'BUY':
                            logger.info(f"📊 Williams %R crossed below -80 (value: {current_wr:.2f})")
                            self.place_buy_order(f"Williams %R crossed below -80 ({current_wr:.2f})")
                        else:
                            logger.info(f"📊 Williams %R crossed below -20 (value: {current_wr:.2f})")
                            self.place_sell_order(f"Williams %R crossed below -20 ({current_wr:.2f})")

                except Exception as e:
                    logger.error(f"Error in live test loop: {e}")

                time.sleep(check_interval)

        except KeyboardInterrupt:
            print("\n")
            logger.info("🛑 Live test stopped by user")
            self.running = False

        print("\n" + "=" * 60)
        print(f"✅ Live test completed! Signals triggered: {signals_triggered}")
        print("=" * 60)
        print("\n👉 Check your Kite app -> Orders to see any placed orders!")
        print("   (Or Orders -> GTT if markets were closed)\n")


def main():
    import argparse

    parser = argparse.ArgumentParser(description='OrderUp Trading Bot')
    parser.add_argument('--test', action='store_true', help='Run in test mode (no real orders)')
    parser.add_argument('--test-signals', action='store_true', help='Run test signals to verify flow')
    parser.add_argument('--place-gtt', choices=['buy', 'sell'], help='Place a real GTT order now')
    parser.add_argument('--live-test', action='store_true', help='Test live workflow (real orders, fast interval)')
    args = parser.parse_args()

    if args.place_gtt:
        # Place a real GTT order
        bot = OrderUpBot(test_mode=False)
        if bot.connect():
            print(f"\n📊 Placing real GTT {args.place_gtt.upper()} order for {bot.symbol}...")
            if args.place_gtt == 'buy':
                bot.place_gtt_buy_order("Manual GTT order via CLI")
            else:
                bot.place_gtt_sell_order("Manual GTT order via CLI")
            print("\n✅ Check your Kite app -> Orders -> GTT to see the order!")
        return

    if args.live_test:
        # Live test - real API, real orders (or GTT fallback), fast interval
        bot = OrderUpBot(test_mode=False)
        bot.run_live_test()
        return

    bot = OrderUpBot(test_mode=args.test or args.test_signals)
    bot.run(force_test_signals=args.test_signals)


if __name__ == "__main__":
    main()
