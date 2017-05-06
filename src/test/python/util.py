import mimetypes
import os
import tempfile
import time
import urllib2

import requests


def download_file(url, filename, api_token, stoptime):
    """Download file at given URL"""
    if not filename:
        filename = url.split('/')[-1]
    try:
        headers = {'Authorization': api_token}
        r = requests.get(url, headers=headers, stream=True)
        while stoptime > time.time() and r.status_code == 404:
            time.sleep(1)
            r = requests.get(url, headers=headers, stream=True)
        if r.status_code != 404:
            with open(filename, 'wb') as f:
                for chunk in r.iter_content(chunk_size=1024):
                    if chunk:  # Filter out keep-alive new chunks
                        f.write(chunk)
                        f.flush()
    except:
        raise
    return filename


def download_file_web(url):
    u = urllib2.urlopen(url)
    file_name = url.split('/')[-1]
    tf = tempfile.NamedTemporaryFile(dir='/tmp')
    downloaded_filename = tf.name + '.' + file_name
    f = open(downloaded_filename, 'wb')

    file_size_dl = 0
    block_sz = 8192
    while True:
        dl_buffer = u.read(block_sz)
        if not dl_buffer:
            break

        file_size_dl += len(dl_buffer)
        f.write(dl_buffer)

    f.close()
    # print "download file: ", downloaded_filename
    return downloaded_filename


def multipart(data, files, boundary, blocksize=1024 * 1024):
    """Creates appropriate body to send with requests.

    The body that is generated will be transferred as chunked data. This assumes the
    following is added to headers: 'Content-Type': 'multipart/form-data; boundary=' + boundary

    Only the actual filedata is chunked, the values in the data is send as is.

    :param data: (key, val) pairs that are send as form data
    :param files:  (key, file) or (key, (file, content-type)) pairs that will be send
    :param boundary: the boundary marker
    :param blocksize: the size of the chunks to send (1MB by default)
    :return:
    """

    # send actual form data
    for tup in data:
        tup_key, tup_value = tup
        yield '--%s\r\n' \
              'Content-Disposition: form-data; name="%s"\r\n\r\n' % (boundary, tup_key)
        yield tup_value
        yield '\r\n'

    # send the files
    for tup in files:
        (tup_key, tup_value) = tup
        if isinstance(tup_value, tuple):
            real_file, content_type = tup_value
            filename = os.path.basename(real_file)
        else:
            real_file = tup_value
            filename = os.path.basename(real_file)
            content_type = mimetypes.guess_type(filename)[0] or 'application/octet-stream'
        with open(real_file, 'rb') as fd:
            yield '--%s\r\n' \
                  'Content-Disposition: form-data; name="%s"; filename="%s"\r\n' \
                  'Content-Type: %s\r\n\r\n' % (boundary, tup_key, filename, content_type)
            while True:
                data = fd.read(blocksize)
                if not data:
                    break
                yield data
        yield '\r\n'
    yield '--%s--\r\n' % boundary
