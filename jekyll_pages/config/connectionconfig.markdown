---
layout: record
title: ConnectionConfig
permalink: /config/connectionconfig/
---

# ConnectionConfig




## Parameters:


### readTimeoutSeconds `int`

if no input is received for the specified time, the request is aborted
                          with a status 408 response TLS handshake was completed.
                          Only complete HTTP frames are recognized, so sending one byte does not
                          reset the timeout. This timeout applies also for keep alive connections.


### writeTimeoutSeconds `int`

if the HTTP response or chunked content is not successfully written
                           within the specified time, the request is aborted.
                           If the upstream server fails to send data within the timeout, the
                           request will be aborted as well.


### responseTimeoutSeconds `int`

if the HTTP response is not arriving within the defined tme,    
                              the request is aborted

