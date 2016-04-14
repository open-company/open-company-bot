# Open Company Bot

## Development

Start a repl with `boot dev`, connect to it using the printed nrepl port.

The `oc.bot` namespace contains `comment` form with everthing needed to get the system
into a running state. Currently the system is able to retrieve messages from an SQS queue
and relay them into a Slack channel.