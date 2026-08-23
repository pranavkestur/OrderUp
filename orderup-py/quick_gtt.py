#!/usr/bin/env python3
"""Quick script to place GTT order using captured request token"""

import os
import socket

# Force IPv4
original_getaddrinfo = socket.getaddrinfo
def forced_ipv4_getaddrinfo(*args, **kwargs):
    responses = original_getaddrinfo(*args, **kwargs)
    return [r for r in responses if r[0] == socket.AF_INET] or responses
socket.getaddrinfo = forced_ipv4_getaddrinfo

from kiteconnect import KiteConnect
from dotenv import load_dotenv, set_key

load_dotenv()

api_key = os.getenv('KITE_API_KEY')
api_secret = os.getenv('KITE_API_SECRET')
request_token = 'u205m7oudoFAlUbUhTo4t6Gh0O8fjoTe'

print('Generating access token...')
kite = KiteConnect(api_key=api_key)

try:
    data = kite.generate_session(request_token, api_secret=api_secret)
    access_token = data['access_token']
    print(f'Access token: {access_token}')

    # Save to .env (co-located with this script)
    env_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.env')
    set_key(env_path, 'KITE_ACCESS_TOKEN', access_token)
    set_key(env_path, 'KITE_TOKEN_DATE', '2026-07-25')
    print('Token saved to .env')

    # Now place GTT order
    kite.set_access_token(access_token)
    profile = kite.profile()
    print(f"Connected as: {profile['user_name']}")

    # Get RELIANCE instrument
    print('Fetching instruments...')
    instruments = kite.instruments('NSE')
    last_price = 3000
    for inst in instruments:
        if inst['tradingsymbol'] == 'RELIANCE':
            last_price = inst.get('last_price', 3000)
            if last_price == 0:
                last_price = 3000
            print(f'RELIANCE last price: {last_price}')
            break

    # Place GTT
    trigger_price = round(last_price * 0.99, 1)
    limit_price = round(last_price * 1.01, 1)

    print(f'Placing GTT BUY order...')
    print(f'Trigger: {trigger_price}, Limit: {limit_price}')

    gtt_id = kite.place_gtt(
        trigger_type=kite.GTT_TYPE_SINGLE,
        tradingsymbol='RELIANCE',
        exchange='NSE',
        trigger_values=[trigger_price],
        last_price=last_price,
        orders=[{
            'transaction_type': kite.TRANSACTION_TYPE_BUY,
            'quantity': 1,
            'order_type': kite.ORDER_TYPE_LIMIT,
            'product': kite.PRODUCT_CNC,
            'price': limit_price
        }]
    )

    print(f'🟢 GTT ORDER PLACED! ID: {gtt_id}')
    print('Check Kite app -> Orders -> GTT')

except Exception as e:
    print(f'Error: {e}')

