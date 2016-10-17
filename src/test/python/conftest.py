import pytest
import requests
import time
import ruamel.yaml


def pytest_addoption(parser):
    parser.addoption("--host", action="store", default="http://localhost:8080",
                     help="Host, including protocol and port. "
                          "For example in the case of Brown Dog production use https://bd-api.ncsa.illinois.edu")
    parser.addoption("--username", action="store", default="alice",
                     help="Username: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--password", action="store", default="fred",
                     help="Password: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--timeout", action="store", default="300",
                     help="requests timeout")
    parser.addoption("--mongo_host", action="store", default="fred",
                     help="Password: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--mongo_db", action="store", default="fred",
                     help="Password: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--mongo_collection", action="store", default="fred",
                     help="Password: Either from crowd or application.conf depending how fence was setup")


@pytest.fixture(scope="module")
def host(request):
    return request.config.getoption("--host")


@pytest.fixture(scope="module")
def username(request):
    return request.config.getoption("--username")


@pytest.fixture(scope="module")
def password(request):
    return request.config.getoption("--password")


@pytest.fixture(scope="module")
def timeout(request):
    return int(request.config.getoption("--timeout"))


@pytest.fixture
def mongo_host(request):
    return request.config.getoption("--mongo_host")


@pytest.fixture
def mongo_db(request):
    return request.config.getoption("--mongo_db")


@pytest.fixture
def mongo_collection(request):
    return request.config.getoption("--mongo_collection")


@pytest.fixture(scope="module")
def api_key(host, username, password):
    url = host + '/keys/'
    print("Getting key from : " + url)
    r = requests.post(url, auth=(username, password))
    r.raise_for_status()
    key = r.json()['api-key']
    assert key != ""
    return key


@pytest.fixture(scope="module")
def api_token(host, username, password, api_key):
    url = host + '/keys/' + api_key + '/tokens'
    print("Getting token from : " + url)
    r = requests.post(url, auth=(username, password))
    r.raise_for_status()
    token = r.json()['token']
    assert token != ""
    return token


def pytest_generate_tests(metafunc):
    if 'conversion_data' in metafunc.fixturenames:
        with open("test_conversion_data.yml", 'r') as f:
            iterations = ruamel.yaml.load(f, ruamel.yaml.RoundTripLoader)
            metafunc.parametrize('conversion_data', [i for i in iterations], ids=id_function)
    if 'extraction_data' in metafunc.fixturenames:
        with open("test_extraction_data.yml", 'r') as f:
            iterations = ruamel.yaml.load(f, ruamel.yaml.RoundTripLoader)
            metafunc.parametrize('extraction_data', [i for i in iterations], ids=id_function)


def id_function(val):
    return val['description']


def download_file(url, filename, api_token, wait):
    """Download file at given URL"""
    if not filename:
        filename = url.split('/')[-1]
    try:
        headers = {'Authorization': api_token}
        r = requests.get(url, headers=headers, stream=True)
        while (wait > 0 and r.status_code == 404):
            time.sleep(1)
            wait -= 1
            r = requests.get(url, headers=headers, stream=True)
        if (r.status_code != 404):
            with open(filename, 'wb') as f:
                for chunk in r.iter_content(chunk_size=1024):
                    if chunk:  # Filter out keep-alive new chunks
                        f.write(chunk)
                        f.flush()
    except:
        raise
    return filename
