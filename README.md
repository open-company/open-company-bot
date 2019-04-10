# [OpenCompany](https://github.com/open-company) Bot 

[![AGPL License](http://img.shields.io/badge/license-AGPL-blue.svg?style=flat)](https://www.gnu.org/licenses/agpl-3.0.en.html)
[![Build Status](https://travis-ci.org/open-company/open-company-bot.svg?branch=master)](https://travis-ci.org/open-company/open-company-bot)
[![Dependencies Status](https://versions.deps.co/open-company/open-company-bot/status.svg)](https://versions.deps.co/open-company/open-company-bot)


## Background

> In the kingdom of glass everything is transparent, and there is no place to hide a dark heart.

> -- [Vera Nazarian](http://www.veranazarian.com/)

Teams struggle to keep everyone on the same page. People are hyper-connected in the moment with chat and email, but it gets noisy as teams grow, and people miss key information. Everyone needs clear and consistent leadership, and the solution is surprisingly simple and effective - **great leadership updates that build transparency and alignment**.

With that in mind we designed [Carrot](https://carrot.io/), a software-as-a-service application powered by the open source [OpenCompany platform](https://github.com/open-company) and a source-available [web UI](https://github.com/open-company/open-company-web).

With Carrot, important company updates, announcements, stories, and strategic plans create focused, topic-based conversations that keep everyone aligned without interruptions. When information is shared transparently, it inspires trust, new ideas and new levels of stakeholder engagement. Carrot makes it easy for leaders to engage with employees, investors, and customers, creating alignment for everyone.

Transparency expectations are changing. Organizations need to change as well if they are going to attract and retain savvy teams, investors and customers. Just as open source changed the way we build software, transparency changes how we build successful companies with information that is open, interactive, and always accessible. Carrot turns transparency into a competitive advantage.

To get started, head to: [Carrot](https://carrot.io/)


## Overview

The OpenCompany Bot handles interacting with OpenCompany users via Slack conversations.


## Local Setup

Prospective users of [Carrot](https://carrot.io/) should get started by going to [Carrot.io](https://carrot.io/). The following local setup is **for developers** wanting to work on the OpenCompany Bot Service.

Most of the dependencies are internal, meaning [Leiningen](https://github.com/technomancy/leiningen) will handle getting them for you. There are a few exceptions:

* [Java 8+](http://www.oracle.com/technetwork/java/javase/downloads/index.html) - a Java 8+ JRE is needed to run Clojure
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

An [AWS S3 bucket](https://aws.amazon.com/s3/) is used to cache Slack banner images for the Bot. Setup an S3 bucket using the AWS Web Console or API with access of "Objects can be public".

Another AWS S3 bucket is used to store the 6 footer image varients.

An API key is needed for [Filestack](https://www.filestack.com/) to build URL's that do image processing.

Before running anything make sure you adjust the values in the config map.

```clojure
{
    :open-company-auth-passphrase "this_is_a_dev_secret" ; JWT secret
    :db-name "open_company_auth_dev"
    :aws-access-key-id "CHANGE-ME"
    :aws-secret-access-key "CHANGE-ME"
    :aws-sqs-bot-queue "CHANGE-ME" ; SQS queue to read inbound notifications/requests
    :aws-sqs-storage-queue "CHANGE-ME" ; SQS queue to send requests to the Storage service
    :aws-s3-digest-banner-bucket "CHANGE-ME" ; S3 bucket for caching digest banners
    :aws-s3-digest-footer-bucket "CHANGE-ME" ; S3 bucket for storing/serving digest footers
    :filestack-api-key "CHANGE-ME"
    :log-level "debug"
}
```

You can also override these settings with environmental variables in the form of `AWS_ACCESS_KEY_ID`, etc. Use environmental variables to provide production secrets when running in production.

You will also need to subscribe the SQS queue to the storage SNS topic. To do this you will need to go to the AWS console and follow these instruction:

Go to the AWS SQS Console and select the bot queue configured above. From the 'Queue Actions' dropdown, select 'Subscribe Queue to SNS Topic'. Select the SNS topic you've configured your Storage Service instance to publish to, and click the 'Subscribe' button.

##### Shared Secrets

A secret, `open-company-auth-passphrase`, is shared between the OpenCompany services for creating and validating [JSON Web Tokens](https://jwt.io/).

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

Distributed under the [GNU Affero General Public License Version 3](https://www.gnu.org/licenses/agpl-3.0.en.html).

Copyright © 2016-2019 OpenCompany, LLC.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU Affero General Public License](https://www.gnu.org/licenses/agpl-3.0.en.html) for more details.
