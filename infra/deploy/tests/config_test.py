"""Checks of the rendered compose configuration (infra/deploy/tests/run.sh, CI job "Deployment config").

The files are rendered with `docker compose config`, so the checks see what Docker would run. The
development render inlines the developer's ../.env into the environment of the services: the checks
never print environment values.
"""
import json
import os
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
DEV = ["infra/docker-compose.yml"]
PROD = DEV + ["infra/deploy/compose.prod.yml"]
PROD_ENV = "infra/deploy/.env.example"
# variables of the developer's shell that would change the render
OVERRIDES = ("WEB_BIND_ADDRESS", "DOMAIN", "IMAGE_PREFIX", "IMAGE_TAG")
SECRET_NAME = re.compile(r"PASSWORD|SECRET|TOKEN|KEY|PASS$")


def render(files, env_file=None, **env):
    cmd = ["docker", "compose"]
    for file in files:
        cmd += ["-f", str(ROOT / file)]
    if env_file:
        cmd += ["--env-file", str(ROOT / env_file)]
    cmd += ["--profile", "app", "config", "--format", "json"]
    # nothing secret from the developer's shell may reach the render (or a failure message)
    process_env = {k: v for k, v in os.environ.items()
                   if k not in OVERRIDES and not SECRET_NAME.search(k.upper())}
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


class ProductionSecrets(unittest.TestCase):
    """Each container gets only the credentials it uses (rendered with .env.example, where every
    secret is the placeholder "change-me")."""

    @classmethod
    def setUpClass(cls):
        cls.services = render(PROD, PROD_ENV)["services"]

    def env(self, service):
        return self.services[service].get("environment") or {}

    def holders(self, key):
        return {name for name in self.services if self.env(name).get(key) == "change-me"}

    def test_broker_password_only_for_services_with_amqp(self):
        # importer has no AMQP client: a parser exploit there must not be able to publish bot events
        self.assertEqual({"core", "scheduler", "notification"}, self.holders("RABBIT_PASSWORD"))
        self.assertEqual("change-me", self.env("rabbitmq").get("RABBITMQ_DEFAULT_PASS"))

    def test_importer_has_no_broker_account_at_all(self):
        keys = set(self.env("importer"))
        self.assertNotIn("RABBIT_USER", keys)
        self.assertNotIn("RABBIT_PASSWORD", keys)

    def test_redis_password_only_for_services_with_a_redis_client(self):
        # scheduler has no Redis client
        self.assertEqual({"core", "importer", "notification", "gateway"},
                         self.holders("SPRING_DATA_REDIS_PASSWORD"))

    def test_edge_containers_hold_no_secrets(self):
        for name in ("caddy", "web"):
            self.assertNotIn("change-me", (self.env(name) or {}).values(), name)

    def test_broker_user_is_not_an_administrator(self):
        mounts = {v.get("target"): v.get("source", "") for v in self.services["rabbitmq"].get("volumes", [])}
        source = mounts.get("/etc/rabbitmq/conf.d/20-rozklad.conf", "")
        self.assertTrue(source.replace("\\", "/").endswith("infra/deploy/rabbitmq/20-rozklad.conf"), mounts)
        conf = (ROOT / "infra/deploy/rabbitmq/20-rozklad.conf").read_text(encoding="utf-8")
        self.assertIn("default_user_tags.administrator = false", conf)


if __name__ == "__main__":
    unittest.main(verbosity=2)
