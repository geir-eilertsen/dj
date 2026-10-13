#!/usr/bin/env bash
# Publishes the LMS plugin so LMS can install it from the repository file repo.xml:
# zips plugin/ (install.xml at the top, as LMS requires), attaches the zip to a GitHub release
# "plugin-v<version>", then points repo.xml at it with its SHA-1 and pushes that.
#
# Bump <version> in plugin/install.xml first: LMS offers an update only for a higher version.
set -euo pipefail
cd "$(dirname "$0")/.."

repo=geir-eilertsen/dj
version=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' plugin/install.xml)
tag="plugin-v$version"
zip="dist/DJ-$version.zip"

if [ -n "$(git status --porcelain plugin repo.xml)" ]; then
	echo "plugin/ or repo.xml has uncommitted changes" >&2
	exit 1
fi
if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
	echo "Release $tag already exists; bump <version> in plugin/install.xml" >&2
	exit 1
fi

rm -rf dist && mkdir dist
(cd plugin && python3 -m zipfile -c "../$zip" $(ls -A))
sha=$(sha1sum "$zip" | cut -d' ' -f1)

gh release create "$tag" "$zip" --repo "$repo" --target "$(git rev-parse HEAD)" \
	--title "DJ plugin $version" \
	--notes "LMS plugin $version. Install it from the repository: see Getting started in the README."

# Only now, with the zip downloadable, does repo.xml point at it.
cat > repo.xml <<XML
<?xml version="1.0" encoding="utf-8"?>
<extensions>
	<details>
		<title lang="EN">DJ</title>
	</details>
	<plugins>
		<plugin name="DJ" version="$version" minTarget="8.0" maxTarget="*">
			<title lang="EN">DJ (AI suggestions)</title>
			<title lang="NO">DJ (AI-forslag)</title>
			<desc lang="EN">AI DJ: Start DJ, moods in your own words and Don't Stop The Music suggestions, with a reason for every song, and upcoming concerts in Europe by the artists you play. Needs the dj service (Docker image geireilertsen/dj).</desc>
			<desc lang="NO">AI-DJ: Start DJ, stemninger med egne ord og forslag til Ikke stopp musikken, med en begrunnelse for hver låt, og kommende konserter i Europa med artistene du spiller. Krever dj-tjenesten (Docker-bildet geireilertsen/dj).</desc>
			<creator>Geir Eilertsen</creator>
			<category>musicservices</category>
			<link>https://github.com/$repo</link>
			<icon>https://raw.githubusercontent.com/$repo/main/plugin/HTML/EN/plugins/DJ/html/images/icon.png</icon>
			<url>https://github.com/$repo/releases/download/$tag/DJ-$version.zip</url>
			<sha>$sha</sha>
		</plugin>
	</plugins>
</extensions>
XML

git add repo.xml
git commit -m "Publish DJ plugin $version in repo.xml"
git push origin HEAD
echo "Published $tag ($sha)"
