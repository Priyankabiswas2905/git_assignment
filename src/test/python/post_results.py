import xmltodict
import datetime
import argparse
import ruamel.yaml
import socket

from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from pymongo import MongoClient
from smtplib import SMTP


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
                logmsg['message'] = testcase['error']['@message']
                logmsg['trace'] = testcase['error']['#text']
            elif 'failure' in testcase:
                msgtype = 'failures'
                logmsg['message'] = testcase['failure']['@message']
                logmsg['trace'] = testcase['failure']['#text']
            elif 'skipped' in testcase:
                msgtype = 'skipped'
                logmsg['message'] = testcase['skipped']['@message']
            else:
                msgtype = 'success'
            if 'system-out' in testcase:
                logmsg['system-out'] = testcase['system-out']
            if 'system-err' in testcase:
                logmsg['system-err'] = testcase['system-err']
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
            msg = MIMEMultipart('alternative')

            msg['From'] = '"%s" <devnull@ncsa.illinois.edu>' % host

            if len(log['failures']) > 0 or len(log['errors']) > 0:
                email_addresses = [r['address'] for r in recipients if r['get_failure'] is True]
                msg['Subject'] = "[%s] Brown Dog Tests Failures" % args.server
            elif len(log['skipped']) > 0:
                email_addresses = [r['address'] for r in recipients if r['get_success'] is True]
                msg['Subject'] = "[%s] Brown Dog Tests Skipped" % args.server
            else:
                email_addresses = [r['address'] for r in recipients if r['get_success'] is True]
                msg['Subject'] = "[%s] Brown Dog Tests Success" % args.server

            msg['To'] = ', '.join(email_addresses)

            # Plain Text version of the email message
            text = ""
            text += "Host         : %s\n" % host
            text += "Total Tests  : %d\n" % total_tests
            text += "Failures     : %d\n" % len(log['failures'])
            text += "Errors       : %d\n" % len(log['errors'])
            text += "Skipped      : %d\n" % len(log['skipped'])
            text += "Success      : %d\n" % len(log['success'])
            text += "Elapsed time : %5.2f seconds\n" % elapsed_time
            if len(log['failures']) > 0:
                text += '++++++++++++++++++++++++++++ FAILURES ++++++++++++++++++++++++++++++++\n'
                for logmsg in log['failures']:
                    text += logmsg_str(logmsg)
            if len(log['errors']) > 0:
                text += '++++++++++++++++++++++++++++ ERRORS ++++++++++++++++++++++++++++++++++\n'
                for logmsg in log['errors']:
                    text += logmsg_str(logmsg)
            if len(log['skipped']) > 0:
                text += '++++++++++++++++++++++++++++ SKIPPED +++++++++++++++++++++++++++++++++\n'
                for logmsg in log['skipped']:
                    text += logmsg_str(logmsg)
            # if len(log['success']) > 0:
            #    body += '++++++++++++++++++++++++++++ SUCCESS +++++++++++++++++++++++++++++++++\n'
            #    for logmsg in log['success']:
            #        body += logmsg_str(logmsg)
            msg.attach(MIMEText(text, 'plain'))

            # HTML version of the email message
            text = "<html><head></head><body>\n"
            text += "<table border=0>\n"
            text += "<tr><th align='left'>Host</th><td>%s</td></tr>\n" % host
            text += "<tr><th align='left'>Total Tests</th><td>%d</td></tr>\n" % total_tests
            text += "<tr><th align='left'>Failures</th><td>%d</td></tr>\n" % len(log['failures'])
            text += "<tr><th align='left'>Errors</th><td>%d</td></tr>\n" % len(log['errors'])
            text += "<tr><th align='left'>Skipped</th><td>%d</td></tr>\n" % len(log['skipped'])
            text += "<tr><th align='left'>Success</th><td>%d</td></tr>\n" % len(log['success'])
            text += "<tr><th align='left'>Elapsed time</th><td>%5.2f seconds</td></tr>\n" % elapsed_time
            text += "</table>\n"
            if len(log['failures']) > 0:
                text += '<h2>FAILURES</h2>\n'
                for logmsg in log['failures']:
                    text += logmsg_html(logmsg)
            if len(log['errors']) > 0:
                text += '<h2>ERRORS</h2>\n'
                for logmsg in log['errors']:
                    text += logmsg_html(logmsg)
            if len(log['skipped']) > 0:
                text += '<h2>SKIPPED</h2>\n'
                for logmsg in log['skipped']:
                    text += logmsg_html(logmsg)
            text += "</body></html>"
            msg.attach(MIMEText(text, 'html'))

            # send the actual message
            mailserver = SMTP(args.mailserver)
            mailserver.sendmail(msg['From'], email_addresses, msg.as_string())
            mailserver.quit()


def report_mongo(host, total_tests, elapsed_time, log):
    """Write the test results to mongo database"""
    if args.mongo_host and args.mongo_db and args.mongo_collection:
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
            'results': log
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
        result += "system out :\n%s\n" % logmsg['system-out']
    if 'system-err' in logmsg:
        result += "system err :\n%s\n" % logmsg['system-err']
    result += "----------------------------------------------------------------------\n"
    return result


def logmsg_html(logmsg):
    result = "<table border=0>\n"
    result += "<tr><th align='left'>Name</th><td>%s</td></tr>\n" % logmsg['name']
    result += "<tr><th align='left'>Classname</th><td>%s</td></tr>\n" % logmsg['classname']
    result += "<tr><th align='left'>Time</th><td>%5.2f seconds</td></tr>\n" % logmsg['time']
    if 'message' in logmsg:
        text = logmsg['message'].replace("\n", "<br/>\n")
        result += "<tr><th align='left' valign='top'>Message</th><td>%s</td></tr>\n" % text
    if 'system-out' in logmsg:
        text = logmsg['system-out'].replace("\n", "<br/>\n")
        result += "<tr><th align='left' valign='top'>System Out</th><td>%s</td></tr>\n" % text
    if 'system-err' in logmsg:
        text = logmsg['system-err'].replace("\n", "<br/>\n")
        result += "<tr><th align='left' valign='top'>System Err</th><td>%s</td></tr>\n" % text
    result += "</table>\n"
    result += "<hr/>"
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
