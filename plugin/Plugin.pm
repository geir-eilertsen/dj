package Plugins::DJ::Plugin;

# Don't Stop The Music mixer that asks the dj suggestion service what to play next.
#
# The service does all the thinking; this plugin only collects seed tracks, makes one
# non-blocking HTTP request and hands the returned URLs to DSTM.

use strict;
use base qw(Slim::Plugin::Base);

use JSON::XS::VersionOneAndTwo;
use URI::Escape qw(uri_escape_utf8);

use Slim::Networking::SimpleAsyncHTTP;
use Slim::Utils::Log;
use Slim::Utils::Prefs;

use constant DSTM_HANDLER    => 'PLUGIN_DJ_DSTM';
use constant NUM_SEED_TRACKS => 5;
use constant NUM_MIX_TRACKS  => 5;

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

	$class->SUPER::initPlugin(@_);
}

sub postinitPlugin {
	my $class = shift;

	# DSTM is a core plugin, but it can be disabled
	if ( Slim::Utils::PluginManager->isEnabled('Slim::Plugin::DontStopTheMusic::Plugin') ) {
		require Slim::Plugin::DontStopTheMusic::Plugin;
		Slim::Plugin::DontStopTheMusic::Plugin->registerHandler(DSTM_HANDLER, \&dontStopTheMusic);
	}
	else {
		$log->warn("Don't Stop The Music is disabled - nothing to register with");
	}
}

# Called by DSTM when the queue is about to run out. $cb MUST be called exactly once on every
# path: DSTM marks the player busy until it is, so a missed callback stops DSTM for that player.
# An empty list makes DSTM fall back to its own behaviour (RandomPlay, if enabled).
sub dontStopTheMusic {
	my ($client, $cb) = @_;

	my $url = eval { _mixUrl($client) };
	if ($@ || !$url) {
		$log->error("Could not build mix request: $@");
		$cb->($client, []);
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
			$cb->($client, $tracks);
		},
		sub {
			my ($http, $error) = @_;
			$log->warn("dj service request failed: $error");
			$cb->($client, []);
		},
		{
			timeout => $prefs->get('timeout'),
		},
	)->get($url);
}

sub _mixUrl {
	my ($client) = @_;

	my $base = $prefs->get('service_url');
	$base =~ s{/+$}{};

	my @seeds = _seedTrackIds($client);

	return sprintf('%s/mix?player=%s&count=%d&seed=%s',
		$base, uri_escape_utf8($client->id), NUM_MIX_TRACKS, join(',', @seeds));
}

# Library ids of the last few tracks in the queue. Tracks that aren't in the library
# (radio, unimported streams) have no positive id and are skipped.
sub _seedTrackIds {
	my ($client) = @_;

	my @playlist = @{ Slim::Player::Playlist::playList($client) || [] };
	my $first = @playlist > NUM_SEED_TRACKS ? @playlist - NUM_SEED_TRACKS : 0;

	return grep { defined $_ && /^\d+$/ && $_ > 0 } map {
		(Slim::Plugin::DontStopTheMusic::Plugin->getMixablePropertiesFromTrack($client, $_))[3]
	} @playlist[$first .. $#playlist];
}

1;
