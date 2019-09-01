# rflick

Web API to download, resize, and save images from [flickr](https://www.flickr.com/) [public images feed](https://www.flickr.com/services/feeds/docs/photos_public/).

## Running

In the project's root folder run the command `clj -m rflick.core` to start [Jetty Web Server](https://www.eclipse.org/jetty/) on port 3000.

## API

There is a single endpoint, `http://localhost:3000`, that is responsible for handling the requests.

### Parameters

* **num-images**

  Optional, integer between 1 and 20.

  Number of images to be downloaded, resized, and saved. Flickr's feed page returns 20 items, that is why the API limit is 20. If not provided, all images returned by the feed will be processed.
* **width**

  Required, integer.

  Width of the resized images.
* **height**

  Required, integer.

  Height of the resized images.

### Response

Empty body, status 202 Accepted.

## Implementation

The processing was implemented as a pipeline of steps connected by [`core.async`](https://github.com/clojure/core.async) channels. For each incoming request the pipeline is built, execution happens and the channels are closed.

The server is a simple [ring](https://github.com/ring-clojure/ring) handler augumented with a couple of middlewares, executed on Jetty via ring-jetty-adapter.

## What can Improve

* **Tests:** tests where developed on the REPL but a test suite would be nice.
* **Error handling:** there is basic error handling that is used during network calls: the `retrying` high-order function. Places that could be improved with error handling are:
  * Writing resized image files to disk.
  * Parsing request query parameters.
* **Functions visibility:** some functions could be private but I decided to keep them public to make testing easier/faster.

Probably a lot more. :-)
