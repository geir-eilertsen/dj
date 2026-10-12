package Plugins::DJ::Plugin;

# Asks the dj suggestion service what to play.
#
# - As a Don't Stop The Music mixer it tops up a queue that is about to run out.
# - As an app (My Apps; pinnable to the home screen) it offers "Start DJ", which replaces the
#   queue with a fresh mix and hands over to DSTM to keep it going.
#
# The service does all the thinking; this plugin only collects seed tracks, makes one
# non-blocking HTTP request and hands the returned URLs to LMS.

use strict;
use base qw(Slim::Plugin::OPMLBased);

use JSON::XS::VersionOneAndTwo;
use Scalar::Util qw(blessed);
use URI::Escape qw(uri_escape_utf8);

use Slim::Networking::SimpleAsyncHTTP;
use Slim::Utils::Log;
use Slim::Utils::Prefs;
use Slim::Utils::Strings qw(cstring);

use constant DSTM_HANDLER     => 'PLUGIN_DJ_DSTM';
use constant NUM_SEED_TRACKS  => 5;
use constant NUM_MIX_TRACKS   => 5;
use constant NUM_START_TRACKS => 10;

my $log = Slim::Utils::Log->addLogCategory({
	'category'     => 'plugin.dj',
	'defaultLevel' => 'WARN',
	'description'  => 'PLUGIN_DJ',
});

my $prefs = preferences('plugin.dj');

$prefs->init({
	service_url => 'http://localhost:9010',
	timeout     => 5,
});

$prefs->setValidate({ 'validator' => 'intlimit', 'low' => 1, 'high' => 30 }, 'timeout');

sub initPlugin {
	my $class = shift;

	if (main::WEBUI) {
		require Plugins::DJ::Settings;
		Plugins::DJ::Settings->new();
	}

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

sub getDisplayName { 'PLUGIN_DJ' }

# The app's menu.
sub handleFeed {
	my ($client, $cb) = @_;

	$cb->({
		items => [{
			name       => cstring($client, 'PLUGIN_DJ_START'),
			type       => 'link',
			url        => \&startDj,
			nextWindow => 'nowPlaying',
		}],
	});
}

# "Start DJ": replace the queue with a fresh mix following the current track, play it,
# and make DJ this player's DSTM mixer so the music keeps going.
sub startDj {
	my ($client, $cb) = @_;

	$client = $client->master;

	my @seeds = grep { defined } _trackId(Slim::Player::Playlist::song($client));

	_requestMix($client, \@seeds, NUM_START_TRACKS, sub {
		my $tracks = shift;

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
				name        => cstring($client, 'PLUGIN_DJ_STARTED'),
				type        => 'text',
				showBriefly => 1,
			}],
		});
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

# Asks the service for $count tracks following @$seeds. $onDone is called exactly once with
# an arrayref of URLs, which is empty if anything went wrong.
sub _requestMix {
	my ($client, $seeds, $count, $onDone) = @_;

	my $url = eval {
		my $base = $prefs->get('service_url');
		$base =~ s{/+$}{};
		sprintf('%s/mix?player=%s&count=%d&seed=%s',
			$base, uri_escape_utf8($client->id), $count, join(',', @$seeds));
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

sub _dstmEnabled {
	return Slim::Utils::PluginManager->isEnabled('Slim::Plugin::DontStopTheMusic::Plugin');
}

1;
