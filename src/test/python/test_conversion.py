import requests
import urllib
from os.path import basename, splitext, getsize
import os
import os.path
import pytest
import time
import mimetypes
import tempfile
from util import *

from conftest import download_file


def test_get_convert(host, api_token, timeout, conversion_data):
    # should this test be skipped
    if 'skip' in conversion_data:
        pytest.skip(conversion_data['skip'])
    
    if 'file_url' in conversion_data:
        convert(host, api_token, timeout, conversion_data, convert_by_url, 'file_url')
    if 'download' in conversion_data:
        conversion_data['file_path'] = download_file_web(conversion_data['file_url'])
        convert(host, api_token, timeout, conversion_data, convert_by_file, 'file_path')

def convert(host, api_token, timeout, conversion_data, convert_func, file_field):
    print "Description     :", conversion_data['description']
    print "Converting from :", conversion_data[file_field]
    print "Converting to   :", conversion_data['output_type']

    endpoint = host + '/dap'
    input_filename = conversion_data['file_url']
    output = conversion_data['output_type']
    tf = tempfile.NamedTemporaryFile(dir='/tmp')
    output_path = tf.name + '.' + output
    r = convert_func(endpoint, api_token, input_filename, output, int(timeout))
    if r.status_code == 200:
        print "Output path     :", output_path
        print "File url        :", r.text
        if basename(output_path):
            output_filename = output_path
        else:
            output_filename = output_path + basename(r.text)
        filename = download_file(r.text, output_filename, api_token, 90)
        assert os.path.isfile(filename), "File was not downloaded"
        print "Downloaded      :", filename
        try:
            assert getsize(filename) != 0, "Resulting file is 0 bytes"
        finally:
            if os.path.isfile(filename):
                os.remove(filename)
            filename = conversion_data[file_field]
            if os.path.isfile(filename):
                os.remove(filename)
    else:
        print "Error converting file", r.status_code
        assert False, "Error retrieving file : %d - %s" % (r.status_code, r.text)


def convert_by_url(endpoint, api_token, input_filename, output, timeout):
    """Pass file to Polyglot Steward."""
    headers = {'Authorization': api_token, 'Accept': 'text/plain'}
    api_call = endpoint + '/convert/' + output + '/' + urllib.quote_plus(input_filename)
    print "API Call        :", api_call
    return requests.get(api_call, headers=headers, timeout=timeout)
    
def convert_by_file(endpoint, api_token, input_filename, output, timeout):
    api_call = endpoint + '/convert/' + output + '/'
    boundary = 'browndog-fence-header'
    files = [('file', (input_filename, mimetypes.guess_type(input_filename)[0] or 'application/octet-stream'))]
    return requests.post(api_call, headers={'Accept': 'application/json', 'Authorization': api_token,
                              'Content-Type': 'multipart/form-data; boundary=' + boundary},
                              data=multipart([], files, boundary, 5 * 1024 * 1024))
