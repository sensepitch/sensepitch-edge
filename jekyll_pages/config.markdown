---
layout: page
title: Configuration Reference
permalink: /config/
---
# Configuration

## Examples

FIXME

## All Configuration

{% assign config_pages = site.pages | sort: 'title' | where: 'layout', 'record' %}

{% for page in config_pages %}
- [{{ page.title }}]({{ page.url | relative_url }})
{% endfor %}
