---
layout: page
title: Configuration Reference
permalink: /config/
---
# Configuration

## Examples

{% assign example_pages = site.pages | sort: 'title' | where: 'layout', 'serenity-test-outcome' %}

{% for page in example_pages %}
- [{{ page.title }}]({{ page.url | relative_url }})
{% endfor %}

## All Configuration

{% assign config_pages = site.pages | sort: 'title' | where: 'layout', 'record' %}

{% for page in config_pages %}
- [{{ page.title }}]({{ page.url | relative_url }})
{% endfor %}
