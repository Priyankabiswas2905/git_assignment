import requests


def test_get_key(host, username, password, timeout):
    url = host + '/keys/'
    print("POST " + url)
    r = requests.post(url, auth=(username, password), timeout=timeout)
    r.raise_for_status()
    key = r.json()['api-key']
    assert key != "", "no key returned"


def test_get_token(host, username, password, api_key, timeout):
    url = host + '/keys/' + api_key + '/tokens'
    print("POST " + url)
    r = requests.post(url, auth=(username, password), timeout=timeout)
    r.raise_for_status()
    token = r.json()['token']
    assert token != "", "no token returned"


def test_delete_token(host, username, password, api_key, timeout):
    # get new token
    get_token_url = host + '/keys/' + api_key + '/tokens'
    print("POST " + get_token_url)
    r_token = requests.post(get_token_url, auth=(username, password), timeout=timeout)
    r_token.raise_for_status()
    token = r_token.json()['token']
    assert token != "", "no token returned"

    # check if token works
    get_polyglot_alive = host + '/dap/alive'
    headers = {'Authorization': token}
    r_alive = requests.get(get_polyglot_alive, headers=headers, timeout=timeout)
    r_alive.raise_for_status()
    print("Get alive with token reponse: " + r_alive.text)

    # delete token
    delete_token_url = host + '/tokens/' + token
    r_delete_token = requests.delete(delete_token_url, auth=(username, password), timeout=timeout)
    r_delete_token.raise_for_status()
    print("Delete token reponse: " + r_delete_token.text)

    # make sure token is deleted
    headers = {'Authorization': token}
    r_alive_no_token = requests.get(get_polyglot_alive, headers=headers, timeout=timeout)
    print("Get alive invalid token reponse: " + r_alive_no_token.text)
    assert r_alive_no_token.status_code == 403, "Did not receive 403 (Forbidden)"
