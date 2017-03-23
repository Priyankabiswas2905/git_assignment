import os
import urllib2
import tempfile

def download_file_web(url):
    u = urllib2.urlopen(url)
    file_name = url.split('/')[-1]
    tf = tempfile.NamedTemporaryFile(dir='/tmp')
    downloaded_filename = tf.name + '.' + file_name
    f = open(downloaded_filename, 'wb')

    file_size_dl = 0
    block_sz = 8192
    while True:
        buffer = u.read(block_sz)
        if not buffer:
            break

        file_size_dl += len(buffer)
        f.write(buffer)

    f.close()
    #print "download file: ", downloaded_filename
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
