# Open Company Bot

## Development

Start a REPL with `boot dev`, connect to it using the printed nREPL port.

The `oc.bot` namespace contains a `comment` form with everything needed to get the system into a running state. There currently is no `start` command that can be called from a terminal.

## Overview

All use cases currently covered by this bot program involve some trigger from the outside world (which is currently done via AWS SQS).

Let’s go through the lifecycle of a *scripted conversation* step-by-step.

1. Initially, as mentioned earlier, some outside event triggers the start of a new conversation. The `oc.bot.sqs` namespaces provides a simple `SQSListener` [component][component] which polls a given SQS queue every 3 seconds.
2. On reception of such message we try to establish a realtime (websocket) connection to Slack or use an existing one if present. These connections are represented as `SlackConnection` components.
3. A `SlackConnection` has a stateful `ConversationManager` which keeps track of ongoing conversations, routes messages to their respective conversations and initiates new ones.
4. Since the message we received from SQS is for initialising new conversations the `ConversationManager` will create a new `Conversation` via `oc.bot.conversation/dispatch!`.
5. The created `Conversation` will be stored in a `predicate-map` by the `ConversationManager`. The predicate is derived from the initial message of a conversation and can be used to associate incoming messages with the correct conversation later on.
6. A `Conversation` is also stateful: a Finite State Machine represents current status of the conversation. All state machines have an initial `[:init]` transition that is intended for the initial set of messages which do not represent a reaction to a user’s message.
7. Now we’ve sent the initial messages through a set of [manifold][manifold] streams back to the `SlackConnection`.

**The user received these messages. The `Conversation` has started.**

1. When a user sends a message to the bot or a channel the bot is part of the `SlackConnection`’s `ConversationManager` is used to find any relevant conversations. If there’s none the message is ignored. If there are any the first matching is used. (If there is a second match a warning is logged. This means our predicate generation function isn’t specific enough.)
2. If a message matches the predicate it’s passed to the `Conversation` through a stream. Every message to a `Conversation` (even the initial ones from SQS) are handled via a transition function (currently `oc.bot.conversation/test-transition-fn`). The transition function now tries to extract meaning from a users message. Meaning here refers to a piece of data that can be used to “advance” the state of the FSM representing the conversation. Depending on the state the FSM is in it might accept different kinds of messages.
3. If a message **fails** to advance the state of the FSM a generic “I don’t understand” message is sent back to the user.
4. If a message **succeeds** to advance the FSM the appropriate response is looked up using the `stage` of the FSM as well as the meaning of the user’s message. The response is then sent to the `SlackConnection`.
5. This process might loop until the conversation’s FSM reaches an accept state in which case it should be removed from the `ConversationManager` (TBD).

## For your orientation 
##### Namespaces

**oc.bot**
Consider this the core of things. It’s very little code but it’s where you go if you want to boot up the entire system.

**oc.bot.sqs**
Code for long-polling SQS, handling messages and deleting them in the case of success.

**oc.bot.message**
Messages sent to users need to be formatted, they need to be parameterised and they need to be read from a description provided inside `scripts/`. This namespace does all of that.

**oc.bot.conversation**
This is responsible for all the state management inside conversations as well as for all message routing from Slack to individual conversations.

**oc.bot.slack**
We want to interact with Slack through their Realtime API, potentially the bot should run on multiple teams for which multiple connections are required. This namespace implements basic building blocks to create, manage and destroy websocket connections to Slacks API.

**oc.bot.utils**
Generic stuff that is not tied to any particular domain.


[component]: https://github.com/stuartsierra/component
[manifold]: https://github.com/ztellman/manifold