import requests


def test_get_key(host, username, password):
    url = host + '/keys/'
    print("POST " + url)
    r = requests.post(url, auth=(username, password))
    print(r.text)
    key = r.json()['api-key']
    assert r.status_code == 200


def test_get_token(host, username, password, api_key):
    url = host + '/keys/' + api_key + '/tokens'
    print("POST " + url)
    r = requests.post(url, auth=(username, password))
    print(r.text)
    token = r.json()['token']
    assert r.status_code == 200