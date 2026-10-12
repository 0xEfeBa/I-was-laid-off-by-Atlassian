# I was laid off by Atlassian

No, not me.

In May 2026, Vasilios Syrakis was laid off by Atlassian after almost eight years on their edge team. Instead of quietly moving on, he recorded a 38-minute video walking through the systems he had built: a self-service broker that let any team spin up their own load balancer, a control plane that kept thousands of Envoy proxies configured, and a sidecar that handled authentication and rate limiting for Jira, Confluence and Bitbucket.

I watched it twice and wanted to build it myself. This repo is that attempt, and the name is a nod to the video that started it.

## What I'm building

A small-scale version of that edge platform, running on three continents, with a Bitbucket-like Git service living on top of it as its first real tenant.

The goal is not a toy. Every claim below should end up backed by a test you can rerun:

- A team registers a service with one command, and it goes live behind Envoy in Germany, the US and Singapore.
- A bad config never hits all three regions at once. It lands in one region first and rolls back on its own if errors go up.
- If a whole region goes down, no acknowledged push is lost.
- One tenant can't hurt another, by accident or on purpose.
- Pushes, pull requests and CI work the way you'd expect from a Git host, with CI jobs running in isolated sandboxes.

## Where it's at

Early days. The skeleton builds and runs, and I'm working through the foundations one piece at a time. I'm writing the core code by hand, because the point is to understand every part of it.

## Stack

Java 21 and Spring Boot for the services, Envoy at the edge, PostgreSQL with Patroni and etcd for data that survives a region going down, React for the UI, and Hetzner servers on three continents for the real tests.

## Credit

All credit for the original design goes to Vasilios Syrakis and the Atlassian edge team. Go watch his video, it's worth it.
