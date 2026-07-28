#!/usr/bin/env python3
"""Place a GTT order with manual price input"""

import os
import socket

# Force IPv4
original_getaddrinfo = socket.getaddrinfo
def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses
socket.getaddrinfo = forced_ipv4_getaddrinfo

from kiteconnect import KiteConnect
from dotenv import load_dotenv

load_dotenv()

api_key = os.getenv('KITE_API_KEY')
api_secret = os.getenv('KITE_API_SECRET')
access_token = os.getenv('KITE_ACCESS_TOKEN')

kite = KiteConnect(api_key=api_key)
kite.set_access_token(access_token)

# Verify connection
profile = kite.profile()
print(f"✅ Connected as: {profile['user_name']}")

# Use current RELIANCE price (approximately)
# Check Google for current price: "RELIANCE NSE price"
last_price = 3050  # Approximate current price

symbol = 'RELIANCE'
quantity = 1

# For BUY GTT: trigger when price FALLS to trigger_price, then buy at limit_price
trigger_price = round(last_price * 0.99, 1)  # Trigger at 1% below
limit_price = round(last_price * 1.01, 1)    # Buy limit at 1% above

print(f"\n📊 Placing GTT BUY order for {symbol}")
print(f"   Last Price: ₹{last_price}")
print(f"   Trigger Price: ₹{trigger_price} (triggers when price falls to this)")
print(f"   Limit Price: ₹{limit_price} (buy at this price or better)")
print(f"   Quantity: {quantity}")

try:
    gtt_id = kite.place_gtt(
        trigger_type=kite.GTT_TYPE_SINGLE,
        tradingsymbol=symbol,
        exchange='NSE',
        trigger_values=[trigger_price],
        last_price=last_price,
        orders=[{
            'transaction_type': kite.TRANSACTION_TYPE_BUY,
            'quantity': quantity,
            'order_type': kite.ORDER_TYPE_LIMIT,
            'product': kite.PRODUCT_CNC,
            'price': limit_price
        }]
    )

    print(f"\n🟢 GTT BUY ORDER PLACED SUCCESSFULLY!")
    print(f"   GTT ID: {gtt_id}")
    print(f"\n👉 Check your Kite app -> Orders -> GTT to see the order!")

except Exception as e:
    print(f"\n❌ GTT ORDER FAILED: {e}")

