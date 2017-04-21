import requests
import pytest


def test_get_outputs(host, api_token, request_timeout):
    url = host + '/dap/outputs'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=request_timeout)
    r.raise_for_status()
    print(r.text)
    assert r.status_code == 200, "Did not receive 200, need better test"


# Special case: This endpoint is slow and does not complete within request_timeout and hence uses processing_timeout.
def test_get_inputs(host, api_token, processing_timeout):
    url = host + '/dap/inputs'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=processing_timeout)
    r.raise_for_status()
    print(r.text)
    assert r.status_code == 200, "Did not receive 200, need better test"


def test_get_inputs_png(host, api_token, request_timeout):
    url = host + '/dap/inputs/png'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=request_timeout)
    r.raise_for_status()
    print(r.text)
    assert r.status_code == 200, "Did not receive 200, need better test"


def test_get_convert(host, api_token, request_timeout):
    url = host + '/dap/convert'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=request_timeout)
    r.raise_for_status()
    print(r.text)
    assert r.status_code == 200, "Did not receive 200, need better test"
