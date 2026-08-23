# OrderUp - Zerodha Kite Trading Bot

An automated trading bot that places buy/sell orders on Zerodha based on technical indicator signals (CCI and Williams %R) on 5-minute timeframe charts.

## Trading Signals

| Indicator | Condition | Action |
|-----------|-----------|--------|
| CCI | Crosses above +100 | SELL |
| CCI | Crosses below -100 | BUY |
| Williams %R | Crosses below -20 | SELL |
| Williams %R | Crosses below -80 | BUY |

## Prerequisites

1. **Zerodha Kite Connect API** - You need a Kite Connect subscription
   - Subscribe at: https://kite.trade/
   - Get API credentials from: https://developers.kite.trade/

2. **Python 3.8+**

## Installation

1. Clone or navigate to this project:
```bash
cd /Users/pranavkestur/Projects/OrderUp
```

2. Create a virtual environment (recommended):
```bash
python3 -m venv venv
source venv/bin/activate
```

3. Install dependencies:
```bash
pip install -r requirements.txt
```

4. Configure environment variables:
```bash
cp .env.example .env
```

5. Edit `.env` with your credentials:
```
KITE_API_KEY=your_api_key_here
KITE_API_SECRET=your_api_secret_here
KITE_ACCESS_TOKEN=your_access_token_here  # Will be generated on first run

# Trading Configuration
STOCK_SYMBOL=RELIANCE
EXCHANGE=NSE
QUANTITY=1
```

## Usage

### Running the Bot

```bash
python trading_bot.py
```

On first run, the bot will:
1. Generate a login URL
2. Ask you to login via the URL
3. Request the `request_token` from the redirect URL
4. Generate and display the `access_token` to save

### Manual Testing

```python
from trading_bot import KiteTradingBot

# Initialize the bot
bot = KiteTradingBot()

# Get current indicator values
indicators = bot.get_current_indicators()
print(indicators)

# Check signals and trade (if conditions met)
result = bot.check_signals_and_trade()
print(result)

# Place orders manually
bot.place_buy_order("RELIANCE", quantity=1, exchange="NSE")
bot.place_sell_order("RELIANCE", quantity=1, exchange="NSE")
```

## File Structure

```
OrderUp/
├── requirements.txt     # Python dependencies
├── .env.example         # Example environment configuration
├── .env                 # Your actual configuration (create this)
├── indicators.py        # CCI and Williams %R calculations
├── trading_bot.py       # Main trading bot logic
└── README.md            # This file
```

## Indicator Calculations

### CCI (Commodity Channel Index)
- **Period**: 20 candles
- **Formula**: `CCI = (Typical Price - SMA) / (0.015 × Mean Deviation)`
- **Typical Price**: `(High + Low + Close) / 3`

### Williams %R
- **Period**: 14 candles
- **Formula**: `%R = ((Highest High - Close) / (Highest High - Lowest Low)) × -100`
- **Range**: -100 to 0

## Important Notes

1. **Access Token Expiry**: Kite access tokens expire daily. You'll need to re-authenticate each trading day.

2. **Market Hours**: The bot should only run during market hours (9:15 AM - 3:30 PM IST).

3. **Risk Management**: This bot does not include stop-loss or position sizing. Add your own risk management logic.

4. **Testing**: Test thoroughly in paper trading or with minimal quantities before using with real money.

5. **API Limits**: Kite Connect has rate limits. The bot checks every 5 minutes to stay within limits.

## Customization

### Change Stock Symbol
Edit `.env`:
```
STOCK_SYMBOL=INFY
```

### Change Indicator Periods
Edit `trading_bot.py`:
```python
df['cci'] = calculate_cci(df, period=14)  # Change from 20 to 14
df['williams_r'] = calculate_williams_r(df, period=10)  # Change from 14 to 10
```

### Use Intraday (MIS) Instead of Delivery (CNC)
```python
bot.place_buy_order("RELIANCE", quantity=1, product="MIS")
```

## Disclaimer

**This software is for educational purposes only. Trading in financial markets involves risk. Always do your own research and never trade with money you cannot afford to lose. The author is not responsible for any financial losses incurred using this software.**

## License

MIT License

