#!/bin/bash

COMPOSE_PROFILES=$(paste -sd, components) docker compose down
