import requests
import pytest

def test_get_outputs(host, api_token, timeout):
    url = host + '/dap/outputs'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=timeout)
    print(r.text)
    assert r.status_code == 200


@pytest.mark.skip(reason="too slow")
def test_get_inputs(host, api_token, timeout):
    url = host + '/dap/inputs'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=timeout)
    print(r.text)
    assert r.status_code == 200


def test_get_inputs_png(host, api_token, timeout):
    url = host + '/dap/inputs/png'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=timeout)
    print(r.text)
    assert r.status_code == 200


def test_get_convert(host, api_token, timeout):
    url = host + '/dap/convert'
    print("GET " + url)
    r = requests.get(url, headers={'Authorization': api_token, 'Accept': 'text/plain'}, timeout=timeout)
    print(r.text)
    assert r.status_code == 200