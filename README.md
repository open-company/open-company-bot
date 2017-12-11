# [OpenCompany](https://github.com/open-company) Bot 

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-bot.svg?branch=master)](https://travis-ci.org/open-company/open-company-bot)
[![Dependencies Status](https://versions.deps.co/open-company/open-company-bot/status.svg)](https://versions.deps.co/open-company/open-company-bot)


## Background

> In the kingdom of glass everything is transparent, and there is no place to hide a dark heart.

> -- [Vera Nazarian](http://www.veranazarian.com/)

Companies struggle to keep everyone on the same page. People are hyper-connected in the moment but still don’t know what’s happening across the company. Employees and investors, co-founders and execs, customers and community, they all want more transparency. The solution is surprisingly simple and effective - great company updates that build transparency and alignment.

With that in mind we designed the [Carrot](https://carrot.io/) software-as-a-service application, powered by the open source [OpenCompany platform](https://github.com/open-company). The product design is based on three principles:

1. It has to be easy or no one will play.
2. The "big picture" should always be visible.
3. Alignment is valuable beyond the team, too.

Carrot simplifies how key business information is shared with stakeholders to create alignment. When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for founders to engage with employees and investors, creating alignment for everyone.

[Carrot](https://carrot.io/) is GitHub for the rest of your company.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

The OpenCompany Bot handles interacting with OpenCompany users via Slack conversations.


## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Bot Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8 JRE is needed to run Clojure
* [Leiningen](https://github.com/technomancy/leiningen) 2.7.1+ - Clojure's build and dependency management tool

#### Java

Chances are your system already has Java 8+ installed. You can verify this with:

```console
java -version
```

If you do not have Java 8+ [download it](http://www.oracle.com/technetwork/java/javase/downloads/index.html) and follow the installation instructions.

#### Leiningen

Leiningen is easy to install:

1. Download the latest [lein script from the stable branch](https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein).
1. Place it somewhere that's on your $PATH (`env | grep PATH`). `/usr/local/bin` is a good choice if it is on your PATH.
1. Set it to be executable. `chmod 755 /usr/local/bin/lein`
1. Run it: `lein` This will finish the installation.

Then let Leiningen install the rest of the dependencies:

```console
git clone https://github.com/open-company/open-company-bot.git
cd open-company-bot
lein deps
```

#### Required Configuration and Secrets

An [AWS SQS queue](https://aws.amazon.com/sqs/) is used to pass messages to the Bot. Setup an SQS Queue and key/secret access to the queue using the AWS Web Console or API.

The Bot needs access to the OpenCompany API endpoint to make updates to open company content and data based on Bot conversations. 

Before running anything make sure you copy `config.edn.template` to `config.edn` and adjust the values in the contained map.

```clojure
{
  :aws-access-key-id "CHANGE-ME"
  :aws-secret-access-key "CHANGE-ME"
  :aws-sqs-bot-queue "https://sqs.REGION.amazonaws.com/CHANGE/ME"
}
```

You can also override these settings with environmental variables in the form of `AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.


## Usage

Run the bot with: `lein start`

Or start a REPL with: `lein repl`

Start the Bot service at the REPL with `(go)` and stop it with `(stop)`.


## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-bot):

[![Build Status](https://travis-ci.org/open-company/open-company-bot.svg?branch=master)](https://travis-ci.org/open-company/open-company-bot)

To run the tests locally:

```console
lein kibit
lein eastwood
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-bot/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/). See `LICENSE` for full text.

Copyright © 2016-2017 OpenCompany, LLC.
