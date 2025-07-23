# PrometheusConfig




## Parameters:


### port `int`


### enableJvmMetrics `boolean`

# DetectCrawlerConfig




## Parameters:


### disableDefault `boolean`


### crawlerTsv `String`

# IpLookupConfig




## Parameters:


### geoIp2 `GeoIp2Config`

# SniConfig




## Parameters:


### domain `String`


### ssl `SslConfig`


### certificateFile `String`


### keyFile `String`

# ListenConfig




## Parameters:


### https `boolean`


### connection `ConnectionConfig`


### ssl `SslConfig`


### letsEncrypt `boolean`


### domains `List<String>`


### sni `List<SniConfig>`


### port `int`

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

# AdmissionConfig




## Parameters:


### serverIpv4Address `String`


### bypass `BypassConfig`


### noBypass `NoBypassConfig`


### detectCrawler `DetectCrawlerConfig`


### tokenGenerator `List<AdmissionTokenGeneratorConfig>`

# SslConfig




## Parameters:


### key `String`


### cert `String`

# UpstreamConfig




## Parameters:


### host `String`

match for the requested host name


### target `String`

target host with optional port number. Names are supported, however the standard
 *             Java DNS resolver is used

# MetricsConfig




## Parameters:


### enable `boolean`


### prometheus `PrometheusConfig`

# AdmissionTokenGeneratorConfig




## Parameters:


### prefix `String`


### secret `String`

# NoBypassConfig




## Parameters:


### uriPrefixes `List<String>`

# GeoIp2Config




## Parameters:


### asnDb `String`


### countryDb `String`

# BypassConfig




## Parameters:


### detectCrawler `DetectCrawlerConfig`


### uriPrefixes `List<String>`


### uriSuffixes `List<String>`


### disableDefaultSuffixes `boolean`


### hosts `List<String>`


### remotes `List<String>`

# RedirectConfig




## Parameters:


### defaultTarget `String`


### passDomains `List<String>`

# ProxyConfig




## Parameters:


### metrics `MetricsConfig`


### listen `ListenConfig`


### admission `AdmissionConfig`


### redirect `RedirectConfig`


### ipLookup `IpLookupConfig`


### upstream `List<UpstreamConfig>`

# PrometheusConfig




## Parameters:


### port `int`


### enableJvmMetrics `boolean`

# DetectCrawlerConfig




## Parameters:


### disableDefault `boolean`


### crawlerTsv `String`

# IpLookupConfig




## Parameters:


### geoIp2 `GeoIp2Config`

# SniConfig




## Parameters:


### domain `String`


### ssl `SslConfig`


### certificateFile `String`


### keyFile `String`

# ListenConfig




## Parameters:


### https `boolean`


### connection `ConnectionConfig`


### ssl `SslConfig`


### letsEncrypt `boolean`


### domains `List<String>`


### sni `List<SniConfig>`


### port `int`

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

# AdmissionConfig




## Parameters:


### serverIpv4Address `String`


### bypass `BypassConfig`


### noBypass `NoBypassConfig`


### detectCrawler `DetectCrawlerConfig`


### tokenGenerator `List<AdmissionTokenGeneratorConfig>`

# SslConfig




## Parameters:


### key `String`


### cert `String`

# UpstreamConfig




## Parameters:


### host `String`

match for the requested host name


### target `String`

target host with optional port number. Names are supported, however the standard
 *             Java DNS resolver is used

# MetricsConfig




## Parameters:


### enable `boolean`


### prometheus `PrometheusConfig`

# AdmissionTokenGeneratorConfig




## Parameters:


### prefix `String`


### secret `String`

# NoBypassConfig




## Parameters:


### uriPrefixes `List<String>`

# GeoIp2Config




## Parameters:


### asnDb `String`


### countryDb `String`

# BypassConfig




## Parameters:


### detectCrawler `DetectCrawlerConfig`


### uriPrefixes `List<String>`


### uriSuffixes `List<String>`


### disableDefaultSuffixes `boolean`


### hosts `List<String>`


### remotes `List<String>`

# RedirectConfig




## Parameters:


### defaultTarget `String`


### passDomains `List<String>`

# ProxyConfig




## Parameters:


### metrics `MetricsConfig`


### listen `ListenConfig`


### admission `AdmissionConfig`


### redirect `RedirectConfig`


### ipLookup `IpLookupConfig`


### upstream `List<UpstreamConfig>`

