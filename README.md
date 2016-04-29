# Open Company Bot

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)

## Development

> Make sure you have Boot and Java 8 installed. For details refer [here](https://github.com/open-company/open-company-web#local-setup).

Before running anything make sure you copy `config.edn.template` to `config.edn` and adjust the values in the contained map.

Start a REPL with `boot dev`.

The `oc.bot` namespace contains a `comment` form with everything needed to get the system into a running state. There currently is no `start` command that can be called from a terminal.

## Overview

All use cases currently covered by this bot program involve some trigger from the outside world (which is currently done via AWS SQS).

Let’s go through the lifecycle of a *scripted conversation* step-by-step.

1. Initially, as mentioned earlier, some outside event triggers the start of a new conversation. The `oc.bot.sqs` namespaces provides a simple `SQSListener` [component][component] which polls a given SQS queue every 3 seconds.
2. On reception of such message we try to establish a realtime (websocket) connection to Slack or use an existing one if present. These connections are represented as `SlackConnection` components.
3. A `SlackConnection` has a stateful `ConversationManager` which keeps track of ongoing conversations, routes messages to their respective conversations and initiates new ones.
4. Since the message we received from SQS is for initialising new conversations the `ConversationManager` will create a new conversation by connecting a few streams inside a closure providing the conversations state via `oc.bot.conversation/dispatch!`.
5. The created stream where incoming messages should be put will be stored in a `predicate-map` by the `ConversationManager`. The predicate is derived from the initial message of a conversation and can be used to associate incoming messages with the correct conversation later on.
6. As mentioned each conversation has an atom to store state. A Finite State Machine represents current status of the conversation. All state machines have an initial `[:init]` transition that is intended for the initial set of messages which do not represent a reaction to a user’s message.
7. Now we’ve sent the initial messages through a set of [manifold][manifold] streams back to the `SlackConnection`.

**The user received these messages. The conversation has started.**

1. When a user sends a message to the bot or a channel the bot is part of the `SlackConnection`’s `ConversationManager` is used to find any relevant conversations. If there’s none the message is ignored. If there are any the first matching is used. (If there is a second match a warning is logged. This means our predicate generation function isn’t specific enough.)
2. If a message matches the predicate it’s put into the conversations stream. Every message put into the stream (even the initial ones from SQS) are handled via a transition function (currently `oc.bot.conversation/transition-fn`). The transition function now tries to extract meaning from a users message. Meaning here refers to a piece of data that can be used to “advance” the state of the FSM representing the conversation. Depending on the state the FSM is in it might accept different kinds of messages.
3. If a message **fails** to advance the state of the FSM a generic “I don’t understand” message is sent back to the user.
4. If a message **succeeds** to advance the FSM the appropriate response is looked up using the `stage` of the FSM as well as the meaning of the user’s message. The response is then sent to the `SlackConnection`.
5. This process might loop until the conversation’s FSM reaches an accept state in which case it should be removed from the `ConversationManager` (TBD).

## For Your Orientation

#### Namespaces

**oc.bot**
Consider this the core of things. It’s very little code but it’s where you go if you want to boot up the entire system.

**oc.api-client**
The start of a OpenCompany API client written in Clojure.

**oc.bot.sqs**
Code for long-polling SQS, handling messages and deleting them in the case of success.

**oc.bot.language**
Various helper functions to check if a string matches a certain pattern (yes, no, etc.). Intended be expanded as requirements evolve.

**oc.bot.message**
Messages sent to users need to be formatted, they need to be parameterised and they need to be read from a description provided inside `scripts/`. This namespace does all of that.

**oc.bot.conversation**
This is responsible for all the state management inside conversations as well as for all message routing from Slack to individual conversations.

**oc.bot.conversation.fsm**
Defines all the state machines that make up conversations and a few helpers related to these.

**oc.bot.slack**
We want to interact with Slack through their Realtime API, potentially the bot should run on multiple teams for which multiple connections are required. This namespace implements basic building blocks to create, manage and destroy websocket connections to Slacks API.

**oc.bot.utils**
Generic stuff that is not tied to any particular domain.

#### Notes on Overall Design

**Regarding ConversationManager:** Most likely one `ConversationManager` is sufficient. Slack message events have a `:team` key which can be used as part of the predicate for incoming messages. (Not all events do have this key but probably not a problem for now.)

**Testing Streams:** A lot of stuff is going through streams. Coming up with a proper testing setup for this is important. 

## Authoring Scripts

Scripts define the messages a bot will send when initiating a conversation or reacting to a user's messages.

All scripts can be found inside `resources/scripts/` and follow the same format (specified in [EDN][edn]):

```clojure
{[stage transition-signal] ["Message 1" "Message 2" "Message 3"]}
```

To understand this format let’s look at how conversations are modelled. Conversations may go through multiple *stages* which can be thought of as “checkpoints” on the way to completing the goal of the conversation.

Within these stages there may be one or more *transitions* representing a user’s message. Because a users message isn’t easily understood by machines we try to extract a *transition-signal* from each message. A transition-signal might look like any of these: `:yes`, `:no`, `:currency` or `:str`. These will be expanded as the bot becomes able to recognize additional types of responses. E.g. a `:user` signal might be introduced.

**An example:** at some point we might ask the user to confirm the companies name. The user might confirm the existing name (`:yes`) or she might choose to change the name (`:no`). After the `:no` signal any response will be interpreted as the desired company name (`:str`). After that another confirmation (`:yes`) is needed to finish the stage or alternatively the user may go back and specify a different name (as often as they want).

![An example stage verifying a piece of information](https://raw.githubusercontent.com/open-company/open-company-bot/master/docs/fact-check-automata.png)

The above example is one *stage*. Let’s assume and identifier for this stage of `:company/name`. The transitions have already been discussed. The skeleton to specify messages for each transition would look like this:

```clojure
{[:company/name :yes] []
 [:company/name :no]  []
 [:company/name :str] []
 [:company/name :yes-after-update] []
```

> **Note** `:yes-after-update` is an extra transformation on the original signal to allow us sending different confirmation messages if the user has changed information.

Now the empty lists after these `[stage transition-signal]` pairs can be filled with messages. Messages may contain special variable fields like `{{company/name}}`. You may also, instead of providing a list of strings, provide a list containing strings and lists of strings. Messages in nested lists will be **chosen randomly**:

```clojure
["Message 1" ["Message 2 v1" "Message 2 v2"] "Message 3"]
```

[component]: https://github.com/stuartsierra/component
[manifold]: https://github.com/ztellman/manifold
[edn]: https://github.com/edn-format/edn

## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-web/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.

## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/). See `LICENSE` for full text.

Copyright © 2015-2016 OpenCompany, Inc.