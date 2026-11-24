# Security

Yggdrasil Mobile runs on your phone and holds the key to your Claude bridge,
a GitHub token, your Mímir device token and a Claude sign-in, and it installs
its own updates. Please report a vulnerability privately, not in a public
issue.

## Reporting

Use **Report a vulnerability** on the repository's Security tab (GitHub
private vulnerability reporting). Say what you found, how to reproduce it, and
what an attacker could reach. You will get an answer within a week.

## In scope

- Another app on the phone using or impersonating the Claude bridge
  (127.0.0.1:8765), or reading the app's tokens.
- Text from notifications, shared text, Mímir or the web that can make the app
  or a Claude session act.
- An update the app installs that you did not release, or a way into the
  release workflow and its signing key.

Releases are signed only in an approved run of the `release` environment, and
the app installs only APKs signed with the same certificate.
