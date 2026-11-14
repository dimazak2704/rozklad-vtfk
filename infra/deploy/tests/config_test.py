"""Checks of the rendered compose configuration (infra/deploy/tests/run.sh, CI job "Deployment config").

The files are rendered with `docker compose config`, so the checks see what Docker would run. The
development render inlines the developer's ../.env into the environment of the services: the checks
never print environment values.
"""
import json
import os
import pathlib
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
DEV = ["infra/docker-compose.yml"]
PROD = DEV + ["infra/deploy/compose.prod.yml"]
PROD_ENV = "infra/deploy/.env.example"
# variables of the developer's shell that would change the render
OVERRIDES = ("WEB_BIND_ADDRESS", "DOMAIN", "IMAGE_PREFIX", "IMAGE_TAG")


def render(files, env_file=None, **env):
    cmd = ["docker", "compose"]
    for file in files:
        cmd += ["-f", str(ROOT / file)]
    if env_file:
        cmd += ["--env-file", str(ROOT / env_file)]
    cmd += ["--profile", "app", "config", "--format", "json"]
    process_env = {k: v for k, v in os.environ.items() if k not in OVERRIDES}
    process_env.update(env)
    out = subprocess.run(cmd, check=True, capture_output=True, text=True, encoding="utf-8", env=process_env)
    return json.loads(out.stdout)


def published(config):
    """{service: [(host_ip, published, target)]} of every service that publishes ports."""
    return {
        name: [(p.get("host_ip", ""), p.get("published"), p.get("target")) for p in service["ports"]]
        for name, service in config["services"].items()
        if service.get("ports")
    }


class DevelopmentStack(unittest.TestCase):
    """The laptop stack holds the real bot token and public demo credentials: nothing may listen on
    the LAN (college or café Wi-Fi)."""

    def assert_loopback_only(self, config):
        ports = published(config)
        self.assertTrue(ports)
        exposed = {name: p for name, p in ports.items() if any(host_ip != "127.0.0.1" for host_ip, _, _ in p)}
        self.assertEqual({}, exposed, "ports published on every interface")

    def test_every_port_is_published_on_loopback_only(self):
        self.assert_loopback_only(render(DEV))

    def test_the_google_mock_too(self):
        self.assert_loopback_only(render(DEV + ["infra/docker-compose.google-mock.yml"]))

    def test_the_site_can_be_opened_to_the_lan_on_purpose(self):
        ports = published(render(DEV, WEB_BIND_ADDRESS="0.0.0.0"))
        self.assertEqual([("0.0.0.0", "8088", 80)], ports["web"])
        others = {name for name, p in ports.items() if name != "web" and any(h != "127.0.0.1" for h, _, _ in p)}
        self.assertEqual(set(), others)


if __name__ == "__main__":
    unittest.main(verbosity=2)
