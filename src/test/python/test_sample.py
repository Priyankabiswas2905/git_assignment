import requests
import pytest


# host = "https://bd-api.ncsa.illinois.edu"
host = "http://localhost:8080"
username = "your username"
password = "your password"
key = ""
token = ""

# def func(x):
#     return x + 1
#
#
# def test_answer():
#     assert func(3) == 4
#
#
# def test_needsfiles(tmpdir):
#     print (tmpdir)
#     assert 0
#
#
# def test_one(tmpdir):
#     print (tmpdir)
#     x = "this"
#     assert 'h' in x
#
#
# def test_two():
#     x = "hello"
#     assert hasattr(x, 'check')
#
#
# def test_one(tmpdir):
#     print (tmpdir)
#     x = "this"
#     assert 'h' in x


def test_get_key():
    global key
    url = host + '/keys/'
    print("calling " + url)
    r = requests.post(url, auth=(username, password))
    print(r.text)
    key = r.json()['api-key']
    assert r.status_code == 200


def test_get_token():
    global key, token
    url = host + '/keys/' + key + '/tokens'
    print("calling " + url)
    r = requests.post(url, auth=(username, password))
    print(r.text)
    token = r.json()['token']
    assert r.status_code == 200


# @pytest.mark.parametrize("accept", ['text/plain', 'text/html'])
def test_get_outputs():
    global token
    url = host + '/dap/outputs'
    print("calling " + url)
    r = requests.get(url, headers={'Authorization': token, 'Accept': 'text/plain'})
    print(r.text)
    assert r.status_code == 200


def test_get_inputs():
    global token
    url = host + '/dap/inputs'
    print("calling " + url)
    r = requests.get(url, headers={'Authorization': token, 'Accept': 'text/plain'})
    print(r.text)
    assert r.status_code == 200


def test_get_inputs_png():
    global token
    url = host + '/dap/inputs/png'
    print("calling " + url)
    r = requests.get(url, headers={'Authorization': token, 'Accept': 'text/plain'})
    print(r.text)
    assert r.status_code == 200


def test_get_convert():
    global token
    url = host + '/dap/convert'
    print("calling " + url)
    r = requests.get(url, headers={'Authorization': token, 'Accept': 'text/plain'})
    print(r.text)
    assert r.status_code == 200


def test_get_convert_png():
    global token
    url = host + '/dap/convert/png'
    print("calling " + url)
    r = requests.get(url, headers={'Authorization': token, 'Accept': 'text/plain'})
    print(r.text)
    assert r.status_code == 200

