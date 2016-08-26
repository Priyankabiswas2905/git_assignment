import pytest
import requests
import urllib
from os.path import basename, splitext
import sys
from conftest import download_file

def test_get_convert(host, api_token, timeout, conversion_data):
    endpoint = host + '/dap'
    input_filename = conversion_data['file_url']
    output = conversion_data['output_type']
    output_path = '/tmp/' + str(0) + '_' + splitext(basename(input_filename))[0] + '.' + output
    r = convert_by_url(endpoint, api_token, input_filename, output, output_path, int(timeout))
    print "Output path ", output_path
    print "File url ", r.text
    if basename(output_path):
        output_filename = output_path
    else:
        output_filename = output_path + basename(r.text)
    filename = download_file(r.text, output_filename, api_token, 90)
    print("Downloaded " + filename + " from " + output_filename)
    assert r.status_code == 200


def convert_by_url(endpoint, api_token, input_filename, output, output_path, timeout):
    """Pass file to Polyglot Steward."""
    output_filename = ''
    headers = {'Authorization': api_token, 'Accept': 'text/plain'}
    api_call = endpoint + '/convert/' + output + '/' + urllib.quote_plus(input_filename)
    print("GET " + api_call)
    try:
        r = requests.get(api_call, headers=headers, timeout=timeout)
        if (r.status_code != 404):
            return r
        else:
            print "404"
    except KeyboardInterrupt:
        sys.exit()
    except:
        e = sys.exc_info()[0]
        print repr(e)
    return output_filename





