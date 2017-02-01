import json
import pytest
import requests
import time
import urllib2


# @pytest.mark.skip(reason="testing conversions")
def test_get_extract(host, api_token, timeout, extraction_data):
    # should this test be skipped
    if 'skip' in extraction_data:
        pytest.skip(extraction_data['skip'])

    print "Description     :", extraction_data['description']
    print "Extractor       :", extraction_data.get('extractor', 'all')
    print "Extracting from :", extraction_data['file_url']
    print "Expecting       :", extraction_data['output']

    endpoint = host + '/dts/api'
    input_url = extraction_data['file_url']
    output = extraction_data['output']
    metadata = extract_by_url(endpoint, api_token, input_url, extraction_data.get('extractor', 'all'), timeout)
    print("Extraction output " + metadata)
    if output.startswith("http://"):
        output = urllib2.urlopen(output).read().strip()
    assert metadata.find(output) != -1, "Could not find expected text"


def extract_by_url(endpoint, api_token, input_url, extractor, timeout):
    headers_json = {'Authorization': api_token, 'Content-Type': 'application/json'}
    headers_del = {'Authorization': api_token}
    api_call = endpoint + '/extractions/upload_url'
    if extractor != 'all':
        api_call += '?extract=0'
    print "API Call        :", api_call

    # upload the file
    r = requests.post(api_call, headers=headers_json, timeout=timeout, data=json.dumps({"fileurl": input_url}))
    r.raise_for_status()
    file_id = r.json()['id']
    print("File id " + file_id)

    # trigger extractor, and check specific output
    if extractor != 'all':
        r = requests.post("%s/files/%s/extractions" % (endpoint, file_id), headers=headers_json,
                          timeout=timeout, data=json.dumps({"extractor": extractor}))
        r.raise_for_status()

        # Wait for the right metadata to be ready
        stoptime = time.time() + timeout
        metadata = []
        while stoptime > time.time():
            r = requests.get('%s/files/%s/metadata.jsonld?extractor=%s' % (endpoint, file_id, extractor),
                             headers=headers_json, timeout=timeout)
            r.raise_for_status()
            if r.text != '[]':
                metadata = r.json()
                break
            time.sleep(1)

    else:
        # Poll until output is ready (optional)
        stoptime = time.time() + timeout
        while stoptime > time.time():
            r = requests.get(endpoint + '/extractions/' + file_id + '/status', headers=headers_json, timeout=timeout)
            r.raise_for_status()
            status = r.json()
            if status['Status'] == 'Done':
                print("Status: Done")
                break
            time.sleep(1)

        # Display extracted content (TODO: needs to be one endpoint)
        r = requests.get(endpoint + '/extractions/' + file_id + '/metadata', headers=headers_json, timeout=timeout)
        r.raise_for_status()
        metadata = r.json()

        r = requests.get(endpoint + '/files/' + file_id + '/technicalmetadatajson', headers=headers_json, timeout=timeout)
        r.raise_for_status()
        metadata["technicalmetadata"] = r.json()

        r = requests.get(endpoint + '/files/' + file_id + '/metadata.jsonld', headers=headers_json, timeout=timeout)
        r.raise_for_status()
        metadata["metadata.jsonld"] = r.json()

    # Delete test files
    r = requests.delete(endpoint + '/files/' + file_id, data={}, headers=headers_del, timeout=timeout)
    r.raise_for_status()

    return json.dumps(metadata)
