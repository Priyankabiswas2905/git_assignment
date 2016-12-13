Functional Testing Suite
========================

Use [py.test](http://doc.pytest.org/) to run functional tests against Fence and https://bd-api.ncsa.illinois.edu/ok.

Install requirements using pip:

```
pip install -r requirements.txt
```

Run tests from the command line (or use an IDE):

```
pytest --host https://bd-api.ncsa.illinois.edu --username yourusernamehere --password yourpasswordhere --junitxml=results.xml (--retry numberofretry)
```

Optional arguments

```
--reruns numberofrerun
```

Conversions inputs are stored in `test_conversion_data.yml` and in `test_extraction_data.yml`. Results are stored in `results.xml`.

Submit results by email to a list of watchers and/or instance of MongoDB:

```
# show on console
post_results.py --console

# send email
post_results.py --mailserver=localhost

# add to mongo
post_results.py --mailserver=localhost --mongo_host=localhost --mongo_db=browndog --mongo_collection=test_results
```


You can add extractions and conversions to the test_conversion_data.yml and test_extraction_data.yml files. If you know
of a test that will fail you can add a skip there with a description of why that test is skipped.
