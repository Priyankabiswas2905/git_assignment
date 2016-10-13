import requests
import json
import time
import urllib2


# @pytest.mark.skip(reason="testing conversions")
def test_get_extract(host, api_token, timeout, extraction_data):
    print(extraction_data['description'])
    endpoint = host + '/dts/api'
    input_url = extraction_data['file_url']
    output = extraction_data['output']
    metadata = extract_by_url(endpoint, api_token, input_url, timeout)
    print("Extraction output " + metadata)
    if output.startswith("http://"):
        output = urllib2.urlopen(output).read().strip()
    assert metadata.find(output) != -1, "Could not find expected text"


def extract_by_url(endpoint, api_token, input_url, timeout):
    headers_json = {'Authorization': api_token, 'Content-Type': 'application/json'}
    headers_del = {'Authorization': api_token}
    api_call = endpoint + '/extractions/upload_url'
    print("POST " + api_call)

    r = requests.post(api_call, headers=headers_json, timeout=timeout, data=json.dumps({"fileurl": input_url}))
    r.raise_for_status()
    file_id = r.json()['id']
    print("File id " + file_id)

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
