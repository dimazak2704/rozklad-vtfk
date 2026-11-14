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


class ProductionEdge(unittest.TestCase):
    def test_only_caddy_publishes_and_only_on_ipv4(self):
        # The compose network is IPv4-only: an IPv6 client would reach Caddy through docker-proxy as the
        # network gateway, and every IPv6 visitor would share one rate-limit bucket and one sign-in
        # throttle. Publishing on 0.0.0.0 keeps IPv6 out (and DEPLOY.md says: no AAAA record).
        ports = published(render(PROD, PROD_ENV))
        self.assertEqual({"caddy"}, set(ports))
        self.assertEqual({("0.0.0.0", "80", 80), ("0.0.0.0", "443", 443)}, {p for p in ports["caddy"]})
        self.assertEqual(3, len(ports["caddy"]))  # 443/tcp and 443/udp


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

    def test_the_development_cli_client_is_off_and_needs_two_switches(self):
        # auth's ProdGuard refuses rozklad-cli on an https issuer unless AUTH_CLI_ALLOW_PROD is set too;
        # both must reach the container for the documented one-off run, and both default to off
        env = self.env("auth")
        self.assertEqual("false", env.get("AUTH_CLI_ENABLED"))
        self.assertEqual("false", env.get("AUTH_CLI_ALLOW_PROD"))

    def test_edge_containers_hold_no_secrets(self):
        for name in ("caddy", "web"):
            self.assertNotIn("change-me", (self.env(name) or {}).values(), name)

    def test_broker_user_is_not_an_administrator(self):
        mounts = {v.get("target"): v.get("source", "") for v in self.services["rabbitmq"].get("volumes", [])}
        source = mounts.get("/etc/rabbitmq/conf.d/20-rozklad.conf", "")
        self.assertTrue(source.replace("\\", "/").endswith("infra/deploy/rabbitmq/20-rozklad.conf"), mounts)
        conf = (ROOT / "infra/deploy/rabbitmq/20-rozklad.conf").read_text(encoding="utf-8")
        self.assertIn("default_user_tags.administrator = false", conf)


class ProductionHardening(unittest.TestCase):
    """After code execution in one container the attacker keeps as little as possible: no new
    privileges through setuid binaries, no capabilities where none are needed, a bounded number of
    processes, and the edge containers cannot write their own filesystem."""

    JAVA = {"auth", "core", "scheduler", "importer", "notification", "gateway"}

    @classmethod
    def setUpClass(cls):
        cls.services = render(PROD, PROD_ENV)["services"]

    def test_no_new_privileges_and_a_process_limit_everywhere(self):
        for name, service in self.services.items():
            with self.subTest(service=name):
                self.assertIn("no-new-privileges:true", service.get("security_opt", []))
                self.assertTrue(0 < service.get("pids_limit", 0) <= 1024)

    def test_no_capabilities_for_the_java_services(self):
        # they run as the non-root user app on ports above 1024
        for name in self.JAVA:
            with self.subTest(service=name):
                self.assertEqual(["ALL"], self.services[name].get("cap_drop"))
                self.assertFalse(self.services[name].get("cap_add"))

    def test_edge_containers_keep_only_what_they_need_and_are_read_only(self):
        self.assertEqual(["ALL"], self.services["caddy"].get("cap_drop"))
        self.assertEqual(["NET_BIND_SERVICE"], self.services["caddy"].get("cap_add"))
        self.assertEqual(["ALL"], self.services["web"].get("cap_drop"))
        # nginx: the root master binds :80 and starts its workers as the user nginx
        self.assertEqual({"CHOWN", "SETGID", "SETUID", "NET_BIND_SERVICE"}, set(self.services["web"].get("cap_add")))
        for name in ("caddy", "web"):
            with self.subTest(service=name):
                self.assertTrue(self.services[name].get("read_only"))

    def test_background_work_cannot_starve_the_database_and_the_entry_points(self):
        def weight(name):
            return self.services[name].get("cpu_shares", 1024)
        for heavy in ("scheduler", "importer", "notification"):
            for vital in ("postgres", "gateway", "auth", "caddy"):
                with self.subTest(heavy=heavy, vital=vital):
                    self.assertLess(weight(heavy), weight(vital))


class Images(unittest.TestCase):
    """Base images that keep getting security fixes, and no tools an image does not use."""

    def dockerfile(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_web_runs_on_a_stable_nginx_branch(self):
        # A mainline tag such as 1.29 is frozen once the next mainline starts; the stable branch
        # (even minor version) gets fixes for a year, and Dependabot proposes the next one.
        match = re.search(r"^FROM nginx:1\.(\d+)-alpine\b", self.dockerfile("web/Dockerfile"), re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertEqual(0, int(match.group(1)) % 2, "nginx 1.%s is a mainline branch" % match.group(1))

    def test_importer_ships_no_libreoffice(self):
        # importer reads DOC with Apache POI and never starts soffice; notification still needs it
        importer = self.dockerfile("services/importer/Dockerfile")
        self.assertNotIn("libreoffice", importer)
        self.assertNotIn("SOFFICE_PATH", importer)
        self.assertIn("libreoffice", self.dockerfile("services/notification/Dockerfile"))

    def test_java_services_run_as_a_non_root_user(self):
        for service in ("auth", "core", "scheduler", "importer", "notification", "gateway"):
            with self.subTest(service=service):
                users = re.findall(r"^USER (\S+)", self.dockerfile(f"services/{service}/Dockerfile"), re.MULTILINE)
                self.assertEqual("app", users[-1] if users else None)


class Workflows(unittest.TestCase):
    """The repository is public and main deploys itself: third-party code in the pipeline is pinned,
    dependencies are watched, and images are rebuilt on fresh base images even without a push."""

    def text(self, path):
        return (ROOT / path).read_text(encoding="utf-8")

    def test_third_party_actions_are_pinned_to_a_commit(self):
        # a moved tag of an action with packages:write would run in the image build; GitHub's own
        # actions/* are trusted like the runner itself
        for workflow in sorted((ROOT / ".github/workflows").glob("*.yml")):
            for ref in re.findall(r"^\s*-?\s*uses:\s*([^\s#]+)", workflow.read_text(encoding="utf-8"), re.MULTILINE):
                if ref.startswith(("actions/", "./")):
                    continue
                with self.subTest(workflow=workflow.name, uses=ref):
                    self.assertRegex(ref, r"@[0-9a-f]{40}$")

    def test_dependabot_watches_every_ecosystem(self):
        config = self.text(".github/dependabot.yml")
        for ecosystem in ("github-actions", "maven", "npm", "docker", "docker-compose"):
            with self.subTest(ecosystem=ecosystem):
                self.assertRegex(config, r'package-ecosystem:\s*"%s"' % re.escape(ecosystem))
        # a PostgreSQL major version cannot read the data volume of the previous one
        self.assertRegex(config, r'dependency-name:\s*"postgres"[^-]*?update-types:\s*\["version-update:semver-major"\]')

    def test_deploy_secrets_are_read_only_by_the_production_environment_job(self):
        # Environment secrets of "production" (deployment branch main) are out of reach for a workflow
        # pushed to another branch; that works only if no other job needs them.
        text = self.text(".github/workflows/deploy.yml")
        jobs = re.split(r"(?m)^  (?=[a-z][a-z0-9_-]*:\s*$)", text.split("\njobs:\n", 1)[1])
        readers = {job.split(":", 1)[0]: job for job in jobs if "secrets.VPS_" in job}
        self.assertEqual({"deploy"}, set(readers))
        self.assertRegex(readers["deploy"], r"(?m)^    environment:\s*\n\s+name: production$")

    def test_images_are_rebuilt_on_a_schedule(self):
        self.assertRegex(self.text(".github/workflows/deploy.yml"), r"(?m)^\s+schedule:\s*\n\s+- cron:")


if __name__ == "__main__":
    unittest.main(verbosity=2)
