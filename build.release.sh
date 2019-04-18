#!/usr/bin/env bash

#gradle clean
gradle \
	-P IS_RELEASE_BUILD=true \
	-P UPLOAD_USER="kman" \
	-P UPLOAD_HOST="kman.mobi" \
	-P UPLOAD_ROOT="/var/www/download" \
	doUpload
