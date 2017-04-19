import requests
import urllib
from os.path import basename, splitext, getsize
import os
import os.path
import pytest
import time


def test_get_convert(host, api_token, request_timeout, processing_timeout, conversion_data):
    # should this test be skipped
    if 'skip' in conversion_data:
        pytest.skip(conversion_data['skip'])

    print "Description     :", conversion_data['description']
    print "Converting from :", conversion_data['file_url']
    print "Converting to   :", conversion_data['output_type']

    stoptime = time.time() + processing_timeout
    endpoint = host + '/dap'
    input_filename = conversion_data['file_url']
    output = conversion_data['output_type']
    output_path = '/tmp/' + str(0) + '_' + splitext(basename(input_filename))[0] + '.' + output
    r = convert_by_url(endpoint, api_token, input_filename, output, request_timeout)
    if r.status_code == 200:
        print "Output path     :", output_path
        print "File url        :", r.text
        if basename(output_path):
            output_filename = output_path
        else:
            output_filename = output_path + basename(r.text)
        filename = download_file(r.text, output_filename, api_token, stoptime)
        assert os.path.isfile(filename), "File was not downloaded"
        print "Downloaded      :", filename
        try:
            assert getsize(filename) != 0, "Resulting file is 0 bytes"
        finally:
            if os.path.isfile(filename):
                os.remove(filename)
    else:
        print "Error converting file", r.status_code
        assert False, "Error retrieving file : %d - %s" % (r.status_code, r.text)


def convert_by_url(endpoint, api_token, input_filename, output, request_timeout):
    """Pass file to Polyglot Steward."""
    headers = {'Authorization': api_token, 'Accept': 'text/plain'}
    api_call = endpoint + '/convert/' + output + '/' + urllib.quote_plus(input_filename)
    print "API Call        :", api_call
    return requests.get(api_call, headers=headers, timeout=request_timeout)


def download_file(url, filename, api_token, stoptime):
    """Download file at given URL"""
    if not filename:
        filename = url.split('/')[-1]
    try:
        headers = {'Authorization': api_token}
        r = requests.get(url, headers=headers, stream=True)
        while (stoptime > time.time() and r.status_code == 404):
            time.sleep(1)
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
