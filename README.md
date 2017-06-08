# OpenCompany Bot 

[![MPL License](http://img.shields.io/badge/license-MPL-blue.svg?style=flat)](https://www.mozilla.org/MPL/2.0/)
[![Build Status](https://travis-ci.org/open-company/open-company-bot.svg?branch=master)](https://travis-ci.org/open-company/open-company-bot)
[![Dependency Status](https://www.versioneye.com/user/projects/592ab183c0295d003f7c8a1f/badge.svg?style=flat)](https://www.versioneye.com/user/projects/592ab183c0295d003f7c8a1f)
[![Roadmap on Trello](http://img.shields.io/badge/roadmap-trello-blue.svg?style=flat)](https://trello.com/b/3naVWHgZ/open-company-development)


## Background

> In the kingdom of glass everything is transparent, and there is no place to hide a dark heart.

> -- [Vera Nazarian](http://www.veranazarian.com/)

Employees and investors, co-founders and execs, they all want more transparency from their startups, but there's no consensus about what it means to be transparent. OpenCompany is a platform that simplifies how key business information is shared with stakeholders.

When information about growth, finances, ownership and challenges is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. OpenCompany makes it easy for founders to engage with employees and investors, creating a sense of ownership and urgency for everyone.

[OpenCompany](https://opencompany.com/) is GitHub for the rest of your company.

To maintain transparency, OpenCompany information is always accessible and easy to find. Being able to search or flip through prior updates empowers everyone. Historical context brings new employees and investors up to speed, refreshes memories, and shows how the company is evolving over time.

Transparency expectations are changing. Startups need to change as well if they are going to attract and retain savvy employees and investors. Just as open source changed the way we build software, transparency changes how we build successful startups with information that is open, interactive, and always accessible. The OpenCompany platform turns transparency into a competitive advantage.

Like the open companies we promote and support, the [OpenCompany](https://opencompany.com/) platform is completely transparent. The company supporting this effort, OpenCompany, LLC, is an open company. The [platform](https://github.com/open-company/open-company-web) is open source software, and open company data is [open data](https://en.wikipedia.org/wiki/Open_data) accessible through the [platform API](https://github.com/open-company/open-company-api).

To get started, head to: [OpenCompany](https://opencompany.com/)


## Overview

The OpenCompany Bot handles interacting with OpenCompany users via Slack conversations.


## Local Setup

Users of the [OpenCompany](https://opencompany.com/) platform should get started by going to [OpenCompany](https://opencompany.com/). The following local setup is **for developers** wanting to work on the platform's Bot software.

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
  :aws-sqs-queue "https://sqs.REGION.amazonaws.com/CHANGE/ME"

  :oc-api-endpoint "http://localhost:3000"
}
```

You can also override these settings with environmental variables in the form of `AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.


## Usage

Run the bot with: `lein start`

Or start a REPL with: `lein repl`

The `oc.bot.app` namespace contains a `comment` form with everything needed to get the system into a running state in the REPL.


## Testing

Tests are run in continuous integration of the `master` and `mainline` branches on [Travis CI](https://travis-ci.org/open-company/open-company-bot):

[![Build Status](https://travis-ci.org/open-company/open-company-bot.svg?branch=master)](https://travis-ci.org/open-company/open-company-bot)

To run the tests locally:

```console
lein kibit
lein eastwood
```


## Participation

Please note that this project is released with a [Contributor Code of Conduct](https://github.com/open-company/open-company-web/blob/mainline/CODE-OF-CONDUCT.md). By participating in this project you agree to abide by its terms.


## License

Distributed under the [Mozilla Public License v2.0](http://www.mozilla.org/MPL/2.0/). See `LICENSE` for full text.

Copyright © 2016-2017 OpenCompany, LLC.