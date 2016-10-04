import xmltodict
from pymongo import MongoClient
import datetime
from smtplib import SMTP
import argparse
import ruamel.yaml
import socket


def main():
    with open(args.junitxml) as fd:
        host = socket.gethostname()
        doc = xmltodict.parse(fd.read())
        total_tests = int(doc['testsuite']['@tests'])
        elapsed_time = float(doc['testsuite']['@time'])
        log = {'errors': list(), 'failures': list(), 'skipped': list(), 'success': list()}

        for testcase in doc['testsuite']['testcase']:
            logmsg = dict()
            logmsg['name'] = testcase['@name']
            logmsg['classname'] = testcase['@classname']
            logmsg['time'] = float(testcase['@time'])
            if 'error' in testcase:
                msgtype = 'errors'
                logmsg['message'] = testcase['error']['#text']
            elif 'failure' in testcase:
                msgtype = 'failures'
                logmsg['message'] = testcase['failure']['#text']
            elif 'skipped' in testcase:
                msgtype = 'skipped'
                logmsg['message'] = testcase['skipped']['@message']
            else:
                msgtype = 'success'
            if 'system-out' in testcase:
                logmsg['system-out'] = testcase['system-out']
            log[msgtype].append(logmsg)

        report_console(host, total_tests, elapsed_time, log)
        report_email(host, total_tests, elapsed_time, log)
        report_mongo(host, total_tests, elapsed_time, log)


def report_console(host, total_tests, elapsed_time, log):
    if args.console:
        message = ""
        message += "Host         : %s\n" % host
        message += "Server       : %s\n" % args.server
        message += "Total Tests  : %d\n" % total_tests
        message += "Failures     : %d\n" % len(log['failures'])
        message += "Errors       : %d\n" % len(log['errors'])
        message += "Skipped      : %d\n" % len(log['skipped'])
        message += "Success      : %d\n" % len(log['success'])
        message += "Elapsed time : %5.2f seconds\n" % elapsed_time
        message += '\n'
        if len(log['failures']) > 0:
            message += "++++++++++++++++++++++++++++ FAILURES ++++++++++++++++++++++++++++++++\n"
            for logmsg in log['failures']:
                message += logmsg_str(logmsg)
        if len(log['errors']) > 0:
            message += "++++++++++++++++++++++++++++ ERRORS ++++++++++++++++++++++++++++++++++\n"
            for logmsg in log['errors']:
                message += logmsg_str(logmsg)
        if len(log['skipped']) > 0:
            message += "++++++++++++++++++++++++++++ SKIPPED +++++++++++++++++++++++++++++++++\n"
            for logmsg in log['skipped']:
                message += logmsg_str(logmsg)
        if len(log['success']) > 0:
            message += "++++++++++++++++++++++++++++ SUCCESS +++++++++++++++++++++++++++++++++\n"
            for logmsg in log['success']:
                message += logmsg_str(logmsg)
        print(message)


def report_email(host, total_tests, elapsed_time, log):
    if args.mailserver:
        with open("watchers.yml", 'r') as f:
            recipients = ruamel.yaml.load(f, ruamel.yaml.RoundTripLoader)
            failure = len(log['failures']) > 0 or len(log['failures']) > 0

            if failure:
                email_addresses = [r['address'] for r in recipients if r['get_failure'] is True]
            else:
                email_addresses = [r['address'] for r in recipients if r['get_success'] is True]

            headers = 'From: "%s" <devnull@ncsa.illinois.edu>\n' % host
            headers += "To: %s\n" % ', '.join(email_addresses)
            if failure:
                headers += "Subject: [%s] Brown Dog Tests Failures\n" % args.server
            else:
                headers += "Subject: [%s] Brown Dog Tests Successful\n" % args.server

            body = ""
            body += "Host         : %s\n" % host
            body += "Total Tests  : %d\n" % total_tests
            body += "Failures     : %d\n" % len(log['failures'])
            body += "Errors       : %d\n" % len(log['errors'])
            body += "Skipped      : %d\n" % len(log['skipped'])
            body += "Success      : %d\n" % len(log['success'])
            body += "Elapsed time : %5.2f seconds\n" % elapsed_time
            if len(log['failures']) > 0:
                body += '++++++++++++++++++++++++++++ FAILURES ++++++++++++++++++++++++++++++++\n'
                for logmsg in log['failures']:
                    body += logmsg_str(logmsg)
            if len(log['errors']) > 0:
                body += '++++++++++++++++++++++++++++ ERRORS ++++++++++++++++++++++++++++++++++\n'
                for logmsg in log['errors']:
                    body += logmsg_str(logmsg)
            if len(log['skipped']) > 0:
                body += '++++++++++++++++++++++++++++ SKIPPED +++++++++++++++++++++++++++++++++\n'
                for logmsg in log['skipped']:
                    body += logmsg_str(logmsg)
            # if len(log['success']) > 0:
            #    body += '++++++++++++++++++++++++++++ SUCCESS +++++++++++++++++++++++++++++++++\n'
            #    for logmsg in log['success']:
            #        body += logmsg_str(logmsg)

            message = "%s\n%s" % (headers, body)
            mailserver = SMTP(args.mailserver)
            for watcher in email_addresses:
                mailserver.sendmail('', watcher, message)
            mailserver.quit()


def report_mongo(host, total_tests, elapsed_time, log):
    """Write the test results to mongo database"""
    if args.mongo_host and args.mongo_db and args.mongo_collection:
        result = log.copy()
        result.pop('success')
        document = {
            'host': host,
            'date': datetime.datetime.utcnow(),
            'server': args.server,
            'elapsed_time': elapsed_time,
            'tests': {
                'total': total_tests,
                'failures': len(log['failures']),
                'errors': len(log['errors']),
                'skipped': len(log['skipped']),
                'success': len(log['success'])
            },
            'results': result
        }
        mc = MongoClient(args.mongo_host)
        db = mc[args.mongo_db]
        tests = db[args.mongo_collection]
        tests.insert(document)


def logmsg_str(logmsg):
    result = "Name       : %s\n" % logmsg['name']
    result += "Classname  : %s\n" % logmsg['classname']
    result += "time       : %5.2f seconds\n" % logmsg['time']
    if 'message' in logmsg:
        result += "Message    : %s\n" % logmsg['message']
    if 'system-out' in logmsg:
        result += "system out : %s\n" % logmsg['system-out']
    result += "----------------------------------------------------------------------\n"
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--mongo_host")
    parser.add_argument("--mongo_db")
    parser.add_argument("--mongo_collection")
    parser.add_argument("--junitxml", default="results.xml", help="junit results xml file generated by pytests")
    parser.add_argument("--mailserver", help="mail server to send update emails out")
    parser.add_argument("--console", action='store_true', help="should output goto console")
    parser.add_argument("--server", default="prod", choices=["DEV", "PROD"], type=str.upper, help="test type")
    args = parser.parse_args()
    # print args.echo
    main()
