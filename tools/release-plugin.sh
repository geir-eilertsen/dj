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
	--notes "${RELEASE_NOTES:-LMS plugin $version.} Install it from the repository: see Getting started in the README."

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
			<desc lang="EN">An AI DJ: Start DJ, moods in your own words ("mellow prog for Sunday morning") and Don't Stop The Music mixes that follow what you play, with a reason for every song, plus upcoming concerts by the artists you play. Note: not a click-and-done plugin. It needs the separate DJ service running in Docker (image geireilertsen/dj), and works best with free Last.fm and paid Anthropic (about $0.0005 a mix) API keys. Setup: see the link.</desc>
			<desc lang="NO">En AI-DJ: Start DJ, stemninger med egne ord og Ikke stopp musikken-mikser som følger det du spiller, med en begrunnelse for hver låt, og kommende konserter med artistene du spiller. Merk: krever den separate DJ-tjenesten i Docker (bildet geireilertsen/dj), og virker best med API-nøkler fra Last.fm (gratis) og Anthropic (ca. $0.0005 per miks). Oppsett: se lenken.</desc>
			<creator>Geir Eilertsen</creator>
			<category>playlists</category>
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
