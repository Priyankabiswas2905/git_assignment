import pytest
import requests
import ruamel.yaml


def pytest_addoption(parser):
    parser.addoption("--host", action="store", default="http://localhost:8080",
                     help="Host, including protocol and port. "
                          "For example in the case of Brown Dog production use https://bd-api.ncsa.illinois.edu")
    parser.addoption("--username", action="store", default="alice",
                     help="Username: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--password", action="store", default="fred",
                     help="Password: Either from crowd or application.conf depending how fence was setup")
    parser.addoption("--request_timeout", action="store", default="5",
                     help="requests timeout")
    parser.addoption("--processing_timeout", action="store", default="300",
                     help="Timeout for completing extraction or conversion process")
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
def request_timeout(request):
    return int(request.config.getoption("--request_timeout"))


@pytest.fixture(scope="module")
def processing_timeout(request):
    return int(request.config.getoption("--processing_timeout"))


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
    # generate key
    url = host + '/keys/'
    r = requests.post(url, auth=(username, password))
    r.raise_for_status()
    key = r.json()['api-key']
    assert key != ""

    # yield key back to rest of test(s)
    yield key

    # delete key after tests are done
    delete_key_url = host + '/keys/' + key
    r_delete_key = requests.delete(delete_key_url, auth=(username, password))
    r_delete_key.raise_for_status()


@pytest.fixture(scope="module")
def api_token(host, username, password, api_key):
    # generate token
    url = host + '/keys/' + api_key + '/tokens'
    r = requests.post(url, auth=(username, password))
    r.raise_for_status()
    token = r.json()['token']
    assert token != ""

    # yield token back to rest of test(s)
    yield token

    # delete token after tests are done
    delete_token_url = host + '/tokens/' + token
    r_delete_token = requests.delete(delete_token_url, auth=(username, password))
    r_delete_token.raise_for_status()


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
