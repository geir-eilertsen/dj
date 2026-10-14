package Plugins::DJ::Plugin;

# Asks the dj suggestion service what to play.
#
# - As a Don't Stop The Music mixer it tops up a queue that is about to run out.
# - As an app (My Apps; pinnable to the home screen) it offers "Start DJ", which replaces the
#   queue with a fresh mix and hands over to DSTM to keep it going, and "Play a mood...", which
#   does the same for a request in the listener's own words ("mellow prog for Sunday morning").
#   The mood keeps steering DJ's suggestions until it's cleared, Start DJ is used, or 4 hours pass.
# - It shows upcoming concerts (from the sources ticked in the settings) in the listener's region by the
#   artist that's playing (track info, and a pop-up in Material Skin), and lists every concert by the
#   artists the listener plays ("Upcoming concerts").
#
# Needs the dj service (Docker image geireilertsen/dj): https://github.com/geir-eilertsen/dj
#
# The service does all the thinking; this plugin only collects seed tracks, makes one
# non-blocking HTTP request and hands the returned URLs to LMS.

use strict;
use base qw(Slim::Plugin::OPMLBased);

use JSON::XS::VersionOneAndTwo;
use Scalar::Util qw(blessed);
use Time::Local qw(timelocal);
use URI::Escape qw(uri_escape_utf8);

use Slim::Menu::TrackInfo;
use Slim::Networking::SimpleAsyncHTTP;
use Slim::Utils::Cache;
use Slim::Utils::DateTime;
use Slim::Utils::Log;
use Slim::Utils::Prefs;
use Slim::Utils::Strings qw(cstring);

use constant DSTM_HANDLER     => 'PLUGIN_DJ_DSTM';
use constant NUM_SEED_TRACKS  => 5;
use constant NUM_MIX_TRACKS   => 5;
use constant NUM_START_TRACKS => 10;
use constant REASON_TTL       => 86400 * 30;
use constant POPUP_SECONDS    => 20;
use constant CONCERT_TTL      => 3600 * 12;
use constant CONCERT_FAIL_TTL => 600;
# An artist's concerts pop up at most this often per player, not for every song of an album
use constant CONCERT_POPUP_EVERY => 3600 * 12;
use constant POPUP_CONCERTS   => 3;

my $log = Slim::Utils::Log->addLogCategory({
	'category'     => 'plugin.dj',
	'defaultLevel' => 'WARN',
	'description'  => 'PLUGIN_DJ',
});

my $prefs = preferences('plugin.dj');

# Track URL => the reason DJ gave for suggesting it, from the mix responses. Track info menus
# are built synchronously, so they read this instead of asking the service. Also holds
# 'concerts:<generation>:<artist>' => the artist's upcoming concerts, for the same reason.
my $cache = Slim::Utils::Cache->new('dj', 1);

# Player id => URL whose reason was last shown, so a song's reason pops up once
my %lastPopup;

# Player id => lower-case artist => when their concerts last popped up
my %concertPopups;

$prefs->init({
	service_url => 'http://localhost:9010',
	timeout     => 10,
});

# 0.4: mixes may wait for Claude, so the old 5 second default is too short
$prefs->migrate(1, sub {
	$prefs->set('timeout', 10) if ($prefs->get('timeout') || 0) < 10;
	1;
});

$prefs->setValidate({ 'validator' => 'intlimit', 'low' => 1, 'high' => 30 }, 'timeout');

sub initPlugin {
	my $class = shift;

	if (main::WEBUI) {
		require Plugins::DJ::Settings;
		Plugins::DJ::Settings->new();
	}

	# Shows in the track info of the playing song (Material: Now Playing, ⋮ More)
	Slim::Menu::TrackInfo->registerInfoProvider( djReason => (
		before => 'contributors',
		func   => \&trackInfoReason,
	) );

	Slim::Menu::TrackInfo->registerInfoProvider( djConcerts => (
		after => 'djReason',
		func  => \&trackInfoConcerts,
	) );

	# Pop the reason and the artist's concerts up on Now Playing when a song starts
	Slim::Control::Request::subscribe(\&onNewSong, [['playlist'], ['newsong']]);

	$class->SUPER::initPlugin(
		feed   => \&handleFeed,
		tag    => 'dj',
		is_app => 1,
		weight => 10,
	);
}

sub postinitPlugin {
	my $class = shift;

	# DSTM is a core plugin, but it can be disabled
	if ( _dstmEnabled() ) {
		require Slim::Plugin::DontStopTheMusic::Plugin;
		Slim::Plugin::DontStopTheMusic::Plugin->registerHandler(DSTM_HANDLER, \&dontStopTheMusic);
	}
	else {
		$log->warn("Don't Stop The Music is disabled - nothing to register with");
	}
}

sub shutdownPlugin {
	Slim::Control::Request::unsubscribe(\&onNewSong);
}

sub getDisplayName { 'PLUGIN_DJ' }

# The app's menu. LMS rebuilds it for every step into it and finds the chosen item by position,
# so the fixed items come first and the ones that depend on the player's moods after them.
sub handleFeed {
	my ($client, $cb) = @_;

	my @items = ({
		name       => cstring($client, 'PLUGIN_DJ_START'),
		type       => 'link',
		url        => \&startDj,
		nextWindow => 'nowPlaying',
	},{
		name        => cstring($client, 'PLUGIN_DJ_MOOD'),
		type        => 'search',
		url         => \&playMood,
		cachesearch => 0,
		nextWindow  => 'nowPlaying',
	},{
		name => cstring($client, 'PLUGIN_DJ_WHY'),
		type => 'link',
		url  => \&whyThisTrack,
	},{
		name => cstring($client, 'PLUGIN_DJ_CONCERTS'),
		type => 'link',
		url  => \&upcomingConcerts,
	});

	my $player = $client ? $client->master->id : '';
	_callService('GET', '/mood', { player => $player }, sub {
		my $moods = shift;

		if (my $current = $moods->{current}) {
			push @items, {
				name => cstring($client, 'PLUGIN_DJ_MOOD_CLEAR', $current->{text}),
				type => 'link',
				url  => \&clearMood,
			};
		}

		# Each recent mood opens "Play" and "Forget"
		for my $text (@{ $moods->{recent} || [] }) {
			push @items, {
				name  => $text,
				type  => 'link',
				items => [{
					name        => cstring($client, 'PLUGIN_DJ_MOOD_PLAY'),
					type        => 'link',
					url         => \&playMood,
					passthrough => [{ text => $text }],
					nextWindow  => 'nowPlaying',
				},{
					name        => cstring($client, 'PLUGIN_DJ_MOOD_FORGET'),
					type        => 'link',
					url         => \&forgetMood,
					passthrough => [{ text => $text }],
					nextWindow  => 'grandparent',
				}],
			};
		}

		$cb->({ items => \@items });
	}, sub {
		# The service is down: the fixed items still work as well as they can
		$cb->({ items => \@items });
	});
}

# Material Skin shows 'info' notifications for a player as a pop-up on every Material window
# showing that player (its send-notif command; timeout in seconds, at most 30).
sub onNewSong {
	my $request = shift;
	my $client  = $request->client() || return;

	return unless Slim::Utils::PluginManager->isEnabled('Plugins::MaterialSkin::Plugin');

	my $song = Slim::Player::Playlist::song($client);
	my $url  = blessed($song) ? $song->url : $song;
	return unless $url;

	# newsong can repeat for the same song (e.g. on resume): pop up once per song change
	return if ($lastPopup{$client->id} || '') eq $url;
	$lastPopup{$client->id} = $url;

	my $reason = $cache->get($url);
	my $artist = _artistOf($client, $url, $song);

	my $popup = sub {
		my ($concerts) = @_;

		# The concerts may arrive after the next song has started
		return unless ($lastPopup{$client->id} || '') eq $url;

		my @parts;
		push @parts, cstring($client, 'PLUGIN_DJ_POPUP') . ' ' . $reason if $reason;
		if ($concerts && @$concerts && _concertPopupDue($client, $artist)) {
			push @parts, _concertSummary($client, $concerts);
		}
		return unless @parts;

		Slim::Control::Request::executeRequest(undef, [
			'material-skin', 'send-notif',
			'type:info',
			'msg:' . join("  \x{00B7}  ", @parts),
			'client:' . $client->id,
			'timeout:' . POPUP_SECONDS,
		]);
	};

	if ($artist) {
		_concertsFor($artist, $popup);
	}
	else {
		$popup->();
	}
}

# Track info item with DJ's reason for a track, if DJ suggested it with one.
sub trackInfoReason {
	my ($client, $url, $track) = @_;

	my $reason = $url && $cache->get($url);
	return unless $reason;

	# One labelled line ("Why DJ chose this: ..."), like Year; a folder needs an extra click.
	# No 'wrap': in menu mode that moves the text out of the list (Material doesn't show it).
	return {
		type  => 'text',
		label => 'PLUGIN_DJ_WHY_CHOSEN',
		name  => $reason,
	};
}

# Track info folder with the artist's upcoming concerts in the listener's region. Built synchronously, so it
# shows what's cached and starts a lookup for next time if nothing is.
sub trackInfoConcerts {
	my ($client, $url, $track, $remoteMeta) = @_;

	my $artist = _artistOf($client, $url, $track, $remoteMeta) || return;
	my $concerts = $cache->get(_concertKey($artist));
	if (!$concerts) {
		_concertsFor($artist, sub {});
		return;
	}
	return unless @$concerts;

	return {
		name  => cstring($client, 'PLUGIN_DJ_CONCERTS_COUNT', scalar @$concerts),
		items => [ map { { name => _concertLine($client, $_), type => 'text' } } @$concerts ],
	};
}

# "Upcoming concerts": every artist the listener plays with concerts coming up, soonest first.
sub upcomingConcerts {
	my ($client, $cb) = @_;

	my $text = sub {
		$cb->({ items => [{ name => cstring($client, shift), type => 'text', wrap => 1 }] });
	};

	_callService('GET', '/concerts', {}, sub {
		my $artists = shift->{artists} || [];
		if (!@$artists) {
			$text->('PLUGIN_DJ_CONCERTS_NONE');
			return;
		}

		my @items;
		for my $artist (@$artists) {
			my $concerts = $artist->{concerts} || [];
			next unless @$concerts;

			# Keep the track info cache in step with what the service knows
			$cache->set(_concertKey($artist->{artist}), $concerts, CONCERT_TTL);

			my $next = $concerts->[0];
			my $name = sprintf('%s: %s, %s', $artist->{artist}, _concertDate($next), $next->{city});
			$name .= ' ' . cstring($client, 'PLUGIN_DJ_CONCERTS_MORE', @$concerts - 1) if @$concerts > 1;

			push @items, {
				name  => $name,
				items => [ map { { name => _concertLine($client, $_), type => 'text' } } @$concerts ],
			};
		}

		$cb->({ items => \@items });
	}, sub {
		$text->('PLUGIN_DJ_CONCERTS_FAILED');
	});
}

# "Why this track?": the reason DJ gave for the song that's playing, if DJ chose it.
sub whyThisTrack {
	my ($client, $cb) = @_;

	return _noPlayer($cb) unless $client;
	$client = $client->master;

	my $answer = sub {
		my ($text) = @_;
		$cb->({ items => [{ name => $text, type => 'text', wrap => 1 }] });
	};

	my $song = Slim::Player::Playlist::song($client);
	my $url  = blessed($song) ? $song->url : $song;
	if (!$url) {
		$answer->(cstring($client, 'PLUGIN_DJ_WHY_NOTHING'));
		return;
	}

	if (my $reason = $cache->get($url)) {
		$answer->($reason);
		return;
	}

	my $base = $prefs->get('service_url');
	$base =~ s{/+$}{};

	Slim::Networking::SimpleAsyncHTTP->new(
		sub {
			my $http = shift;
			my $reason = eval { from_json($http->content)->{reason} };
			$answer->($reason || cstring($client, 'PLUGIN_DJ_WHY_UNKNOWN'));
		},
		sub {
			# 404 when DJ didn't choose the track (or gave no reason); anything else is a failure
			my ($http, $error) = @_;
			$log->info("No reason from dj service: $error") if main::INFOLOG && $log->is_info;
			$answer->(cstring($client, 'PLUGIN_DJ_WHY_UNKNOWN'));
		},
		{
			timeout => $prefs->get('timeout'),
		},
	)->get(sprintf('%s/why?player=%s&url=%s', $base, uri_escape_utf8($client->id), uri_escape_utf8($url)));
}

# "Start DJ": end any mood, replace the queue with a fresh mix following the current track,
# play it, and make DJ this player's DSTM mixer so the music keeps going.
sub startDj {
	my ($client, $cb) = @_;

	return _noPlayer($cb) unless $client;
	$client = $client->master;

	my @seeds = grep { defined } _trackId(Slim::Player::Playlist::song($client));

	my $start = sub {
		_requestMix($client, \@seeds, NUM_START_TRACKS, sub {
			_playMix($client, $cb, shift, cstring($client, 'PLUGIN_DJ_STARTED'));
		});
	};

	# Whether or not clearing works, start: a mood left behind still makes a good mix
	_callService('POST', '/mood/clear', { player => $client->id }, $start, $start);
}

# "Play a mood...": the listener's request, typed or picked from the recent ones. Sets it as the
# player's mood, then starts a fresh mix for it like Start DJ.
sub playMood {
	my ($client, $cb, $args, $pass) = @_;

	return _noPlayer($cb) unless $client;
	$client = $client->master;

	my $text = $pass && $pass->{text};
	$text = $args->{search} if !defined $text && $args;
	$text = '' unless defined $text;
	$text =~ s/^\s+|\s+$//g;

	my $fail = sub {
		$cb->({ items => [{ name => cstring($client, 'PLUGIN_DJ_MOOD_FAILED'), type => 'text' }] });
	};

	if ($text eq '') {
		$fail->();
		return;
	}

	_callService('POST', '/mood', { player => $client->id, text => $text }, sub {
		my $mood = shift;
		_requestMix($client, [], NUM_START_TRACKS, sub {
			_playMix($client, $cb, shift, cstring($client, 'PLUGIN_DJ_STARTED_MOOD', $mood->{text} || $text));
		}, 1);
	}, $fail);
}

sub clearMood {
	my ($client, $cb) = @_;

	return _noPlayer($cb) unless $client;
	$client = $client->master;

	my $reply = sub {
		my ($token) = @_;
		$cb->({ items => [{ name => cstring($client, $token), type => 'text', showBriefly => 1 }] });
	};

	_callService('POST', '/mood/clear', { player => $client->id },
		sub { $reply->('PLUGIN_DJ_MOOD_CLEARED') },
		sub { $reply->('PLUGIN_DJ_START_FAILED') });
}

# Takes a request off the player's recent moods. A mood that's on stays on.
sub forgetMood {
	my ($client, $cb, $args, $pass) = @_;

	return _noPlayer($cb) unless $client;
	$client = $client->master;

	my $reply = sub {
		my ($token) = @_;
		$cb->({ items => [{ name => cstring($client, $token), type => 'text', showBriefly => 1 }] });
	};

	_callService('POST', '/mood/forget', { player => $client->id, text => ($pass && $pass->{text}) || '' },
		sub { $reply->('PLUGIN_DJ_MOOD_FORGOTTEN') },
		sub { $reply->('PLUGIN_DJ_START_FAILED') });
}

# Replaces the queue with $tracks, plays them and makes DJ the player's DSTM mixer.
sub _playMix {
	my ($client, $cb, $tracks, $startedText) = @_;

	if (!@$tracks) {
		$cb->({ items => [{ name => cstring($client, 'PLUGIN_DJ_START_FAILED'), type => 'text' }] });
		return;
	}

	# LMS's own random mix would keep DSTM from calling us; a no-op if it isn't active
	if ( Slim::Utils::PluginManager->isEnabled('Slim::Plugin::RandomPlay::Plugin') ) {
		$client->execute(['randomplay', 'disable']);
	}

	$client->execute(['playlist', 'playtracks', 'listRef', $tracks]);

	if ( _dstmEnabled() ) {
		preferences('plugin.dontstopthemusic')->client($client)->set('provider', DSTM_HANDLER);
	}

	$cb->({
		items => [{
			name        => $startedText,
			type        => 'text',
			showBriefly => 1,
		}],
	});
}

# Called by DSTM when the queue is about to run out. $cb MUST be called exactly once on every
# path: DSTM marks the player busy until it is, so a missed callback stops DSTM for that player.
# An empty list makes DSTM fall back to its own behaviour (RandomPlay, if enabled).
sub dontStopTheMusic {
	my ($client, $cb) = @_;

	my @seeds = eval { _queueTailTrackIds($client) };
	$log->error("Could not read seed tracks: $@") if $@;

	_requestMix($client, \@seeds, NUM_MIX_TRACKS, sub {
		$cb->($client, shift);
	});
}

# Asks the service for $count tracks following @$seeds, or starting a new queue if $fresh.
# $onDone is called exactly once with an arrayref of URLs, which is empty if anything went wrong.
sub _requestMix {
	my ($client, $seeds, $count, $onDone, $fresh) = @_;

	my $url = eval {
		my $base = $prefs->get('service_url');
		$base =~ s{/+$}{};
		sprintf('%s/mix?player=%s&count=%d&seed=%s%s',
			$base, uri_escape_utf8($client->id), $count, join(',', @$seeds), $fresh ? '&fresh=1' : '');
	};
	if ($@ || !$url) {
		$log->error("Could not build mix request: $@");
		$onDone->([]);
		return;
	}

	main::INFOLOG && $log->is_info && $log->info("Requesting mix: $url");

	Slim::Networking::SimpleAsyncHTTP->new(
		sub {
			my $http = shift;

			my $tracks = eval {
				my $response = from_json($http->content);
				for my $track (@{ $response->{tracks} || [] }) {
					$cache->set($track->{url}, $track->{reason}, REASON_TTL) if $track->{url} && $track->{reason};
				}
				[ grep { $_ } map { $_->{url} } @{ $response->{tracks} || [] } ];
			};

			if ($@) {
				$log->error("Invalid response from dj service: $@");
				$tracks = [];
			}

			main::INFOLOG && $log->is_info && $log->info(sprintf("Got %d tracks from dj service", scalar @$tracks));
			$onDone->($tracks);
		},
		sub {
			my ($http, $error) = @_;
			$log->warn("dj service request failed: $error");
			$onDone->([]);
		},
		{
			timeout => $prefs->get('timeout'),
		},
	)->get($url);
}

# Calls the service: GET with %$params in the query, POST with them as a form. Exactly one of
# $onOk (with the decoded JSON response) or $onError is called.
sub _callService {
	my ($method, $path, $params, $onOk, $onError) = @_;

	my $base = $prefs->get('service_url');
	$base =~ s{/+$}{};
	my $query = join('&', map { $_ . '=' . uri_escape_utf8($params->{$_}) } sort keys %$params);

	my $http = Slim::Networking::SimpleAsyncHTTP->new(
		sub {
			my $response = eval { from_json(shift->content) };
			if ($@ || ref $response ne 'HASH') {
				$log->error("Invalid response from dj service for $path: $@");
				$onError->();
				return;
			}
			$onOk->($response);
		},
		sub {
			my ($http, $error) = @_;
			$log->warn("dj service request $method $path failed: $error");
			$onError->();
		},
		{
			timeout => $prefs->get('timeout'),
		},
	);

	if ($method eq 'POST') {
		$http->post($base . $path, 'Content-Type' => 'application/x-www-form-urlencoded', $query);
	}
	else {
		$http->get($base . $path . '?' . $query);
	}
}

# Calls $cb exactly once with the artist's upcoming concerts (an arrayref), from the cache or the
# service. A failed lookup gives an empty list, and is retried after CONCERT_FAIL_TTL.
sub _concertsFor {
	my ($artist, $cb) = @_;

	my $key = _concertKey($artist);
	if (my $concerts = $cache->get($key)) {
		$cb->($concerts);
		return;
	}

	_callService('GET', '/concerts', { artist => $artist }, sub {
		my $concerts = shift->{concerts} || [];
		$cache->set($key, $concerts, CONCERT_TTL);
		$cb->($concerts);
	}, sub {
		$cache->set($key, [], CONCERT_FAIL_TTL);
		$cb->([]);
	});
}

# The concert sources changed in the settings: forget every cached artist's concerts
sub concertSourcesChanged {
	$prefs->set('concert_generation', ($prefs->get('concert_generation') || 0) + 1);
}

sub _concertKey { 'concerts:' . ($prefs->get('concert_generation') || 0) . ':' . lc(shift) }

sub _concertPopupDue {
	my ($client, $artist) = @_;

	my $shown = $concertPopups{$client->id} ||= {};
	my $last  = $shown->{lc $artist} || 0;
	return if time() - $last < CONCERT_POPUP_EVERY;

	$shown->{lc $artist} = time();
	return 1;
}

# "On tour: 14.11.2026 Oslo, 01.12.2026 London, 03.12.2026 Paris (+9 more)"
sub _concertSummary {
	my ($client, $concerts) = @_;

	my @shown = grep { defined } @$concerts[0 .. POPUP_CONCERTS - 1];
	my $text = cstring($client, 'PLUGIN_DJ_CONCERTS_POPUP') . ' '
		. join(', ', map { _concertDate($_) . ' ' . $_->{city} } @shown);
	$text .= ' ' . cstring($client, 'PLUGIN_DJ_CONCERTS_MORE', @$concerts - @shown) if @$concerts > @shown;

	return $text;
}

# "14.11.2026 20:00 · Sentrum Scene, Oslo (NO)", plus the bill when it's a line-up
# ("· Enslaved + Sólstafir"), which shows when the artist is the support act
sub _concertLine {
	my ($client, $concert) = @_;

	my $when = _concertDate($concert);
	$when .= ' ' . $concert->{time} if $concert->{time};
	my $where = join(', ', grep { $_ } $concert->{venue}, $concert->{city});
	$where .= " ($concert->{country})" if $concert->{country};

	my $line = "$when \x{00B7} $where";
	$line .= " \x{00B7} $concert->{title}" if ($concert->{title} || '') =~ / \+ /;

	return $line;
}

# The venue's local date (YYYY-MM-DD) in the server's short date format
sub _concertDate {
	my ($concert) = @_;

	my ($y, $m, $d) = ($concert->{date} || '') =~ /^(\d{4})-(\d\d)-(\d\d)$/ or return $concert->{date} || '';
	my $epoch = eval { timelocal(0, 0, 12, $d, $m - 1, $y) } or return $concert->{date};

	return Slim::Utils::DateTime::shortDateF($epoch);
}

# The artist of a track, for library tracks and ones played straight from a streaming service.
sub _artistOf {
	my ($client, $url, $track, $remoteMeta) = @_;

	my $artist = $remoteMeta && $remoteMeta->{artist};

	if (!$artist && blessed($track) && $track->can('artistName')) {
		$artist = eval { $track->artistName };
	}

	if (!$artist && $url && Slim::Music::Info::isRemoteURL($url)) {
		my $handler = Slim::Player::ProtocolHandlers->handlerForURL($url);
		if ($handler && $handler->can('getMetadataFor')) {
			my $meta = eval { $handler->getMetadataFor($client, $url) };
			$artist = $meta->{artist} if ref $meta eq 'HASH';
		}
	}

	return unless $artist;
	$artist =~ s/^\s+|\s+$//g;
	return $artist;
}

# Ids of the last few tracks in the queue.
sub _queueTailTrackIds {
	my ($client) = @_;

	my @playlist = @{ Slim::Player::Playlist::playList($client) || [] };
	my $first = @playlist > NUM_SEED_TRACKS ? @playlist - NUM_SEED_TRACKS : 0;

	return grep { defined } map { _trackId($_) } @playlist[$first .. $#playlist];
}

# The LMS id of a track object or URL. Library tracks have positive ids; tracks played
# straight from a streaming service have negative ones, which the service matches against
# the player's queue to learn their artist.
sub _trackId {
	my ($track) = @_;

	return unless $track;

	$track = Slim::Schema->objectForUrl($track) if !blessed($track) && Slim::Music::Info::isURL($track);

	my $id = blessed($track) ? $track->id : undef;
	return defined $id && $id =~ /^-?\d+$/ && $id != 0 ? $id : undef;
}

# Menus opened without a player (e.g. a web page with none connected) can't play anything
sub _noPlayer {
	my ($cb) = @_;
	$cb->({ items => [{ name => cstring(undef, 'PLUGIN_DJ_NO_PLAYER'), type => 'text' }] });
	return;
}

sub _dstmEnabled {
	return Slim::Utils::PluginManager->isEnabled('Slim::Plugin::DontStopTheMusic::Plugin');
}

1;
