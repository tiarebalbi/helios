#!/usr/bin/env python
# -*- coding: UTF-8 -*-

from __future__ import print_function
import getpass

import json
import requests
import sys
import urllib

from argparse import ArgumentParser
from urlparse import urljoin
from requests.exceptions import ConnectionError
import time

DEFAULT_MASTER = 'http://localhost:5801'
DEFAULT_USERNAME = getpass.getuser()
RETRY_TIMEOUT_MILLIS = 60000


current_milli_time = lambda: int(round(time.time() * 1000))


class TimeoutException(Exception):
    pass


def std(*args, **kwargs):
    print(*args, file=sys.stdout, **kwargs)


def err(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)


class DefaultHelpParser(ArgumentParser):
    def error(self, message):
        sys.stderr.write('error: %s\n' % message)
        self.print_help()
        sys.exit(2)


class Client(object):
    """Helios REST API Client"""
    def __init__(self, master):
        super(Client, self).__init__()
        self.__master = master

    @staticmethod
    def __connect(req_func, uri, params={}):
        deadline = current_milli_time() + RETRY_TIMEOUT_MILLIS
        while current_milli_time() < deadline:
            try:
                r = req_func(uri, params=params)
                return r
            except ConnectionError as e:
                err('Failed to connect, retrying in 5 seconds.')
                time.sleep(5)
        raise TimeoutException('Timed out connecting to master.')

    def __uri(self, resource, path_params={}):
        encoded_path_params = {k: urllib.quote(v) for k, v in path_params.iteritems()}
        return urljoin(self.__master, resource.format(**encoded_path_params))

    def __get(self, resource, path_params={}, params={}):
        r = self.__connect(requests.get, self.__uri(resource, path_params=path_params), params=params)
        r.raise_for_status()
        return r

    def hosts(self):
        return self.__get('/hosts').json()

    def jobs(self, q=''):
        return self.__get('/jobs', params=dict(q=q)).json()

    def job(self, job_id):
        return self.__get('/jobs/{job_id}', path_params=dict(job_id=job_id)).json()

    def host_status(self, name):
        return self.__get('/hosts/{name}/status', path_params=dict(name=name)).json()

    def version(self):
        return self.__get('/version').text


def pretty_json(value):
    return json.dumps(value, sort_keys=True, indent=2, separators=(',', ': '))


def cmd_hosts(client, args):
    for host in client.hosts():
        std(host)


def cmd_jobs(client, args):
    for job in client.jobs():
        std(job)


def cmd_job(client, args):
    jobs = client.jobs(q=args.id)
    if not jobs:
        return
    if len(jobs) > 1:
        err('Ambigous job reference')
        return 1
    job = jobs.itervalues().next()
    std(pretty_json(job))


def cmd_host(client, args):
    host = client.host_status(args.name)
    if host:
        std(pretty_json(host))


def cmd_version(client, args):
    std(client.version())


def main():
    parser = DefaultHelpParser(description='Spotify Helios CLI')

    subparsers = parser.add_subparsers(title='commands', description='')

    def add_global_args(parser):
        parser.add_argument('-z', '--master',
                            help='Master to connect to. (default: %s)' % DEFAULT_MASTER,
                            default=DEFAULT_MASTER)
        parser.add_argument('-d', '--domains',
                            help='List of comma-separated domains. (default: [])',
                            default='[]')
        parser.add_argument('--srv-name',
                            help='Master\'s SRV name. (default: helios)',
                            default='helios')
        parser.add_argument('-u', '--username',
                            help='username (default: %s)' % DEFAULT_USERNAME,
                            default=DEFAULT_USERNAME)
        parser.add_argument('-v', '--verbose', action='count', help='(default: 0)', default=0)
        parser.add_argument('--json', help='JSON output (default: false)', default=False)

    def command(f, *args, **kwargs):
        parser = subparsers.add_parser(*args, **kwargs)
        add_global_args(parser)
        parser.set_defaults(func=f)
        return parser

    add_global_args(parser)

    command(cmd_version, 'version', help='check master version')
    command(cmd_hosts, 'hosts', help='list hosts')
    command(cmd_jobs, 'jobs', help='list jobs')

    parser_job = command(cmd_job, 'job', help='inspect job')
    parser_job.add_argument('id', help='job id')

    parser_host = command(cmd_host, 'host', help='inspect host')
    parser_host.add_argument('name', help='host name')

    args = parser.parse_args()
    client = Client(args.master)
    status = args.func(client, args)

    if status:
        sys.exit(status)


if __name__ == "__main__":
    main()
