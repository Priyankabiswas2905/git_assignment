import requests
import urllib
from os.path import basename, splitext, getsize
import os
import os.path
import sys
import time

from conftest import download_file


# @pytest.mark.skip(reason="testing extractions")
def test_get_convert(host, api_token, timeout, conversion_data):
    print("Description     :", conversion_data['description'])
    print("Converting from :", conversion_data['file_url'])
    print("Converting to   :", conversion_data['output_type'])
    endpoint = host + '/dap'
    input_filename = conversion_data['file_url']
    output = conversion_data['output_type']
    output_path = '/tmp/' + str(0) + '_' + splitext(basename(input_filename))[0] + '.' + output
    r = convert_by_url(endpoint, api_token, input_filename, output, int(timeout))
    if r and r.status_code == 200:
        print("Output path     :", output_path)
        print("File url        :", r.text)
        if basename(output_path):
            output_filename = output_path
        else:
            output_filename = output_path + basename(r.text)
        filename = download_file(r.text, output_filename, api_token, 90)
        print("Downloaded      :", filename)
        try:
            assert getsize(filename) != 0, "Resulting file is 0 bytes"
        finally:
            if os.path.isfile(filename):
                os.remove(filename)
    elif r:
        print("Error converting file" + r.status_code)
        assert False, "Error retrieving file : %d - %s" % (r.status_code, r.text)
    else:
        print("Error converting file")
        assert False, "Timeout retrieving file."


def convert_by_url(endpoint, api_token, input_filename, output, timeout):
    """Pass file to Polyglot Steward."""
    headers = {'Authorization': api_token, 'Accept': 'text/plain'}
    api_call = endpoint + '/convert/' + output + '/' + urllib.quote_plus(input_filename)
    print("GET " + api_call)

    stoptime = time.time() + timeout
    while stoptime > time.time():
        r = requests.get(api_call, headers=headers, timeout=timeout)
        if r.status_code != 404:
            return r
    return None
