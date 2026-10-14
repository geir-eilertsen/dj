package Plugins::DJ::Settings;

use strict;
use base qw(Slim::Web::Settings);

use Slim::Utils::Prefs;

my $prefs = preferences('plugin.dj');

sub name {
	return Slim::Web::HTTP::CSRF->protectName('PLUGIN_DJ');
}

sub page {
	return Slim::Web::HTTP::CSRF->protectURI('plugins/DJ/settings/basic.html');
}

sub prefs {
	return ($prefs, qw(service_url timeout));
}

# The concert sources live in the service (its daily scan needs them), so the page asks it for the
# list and sends the ticks back to it. Rendered once the service has answered (or failed to).
sub handler {
	my ($class, $client, $params, $callback, @args) = @_;

	my $render = sub {
		my $providers = shift;

		if ($providers) {
			$params->{concert_providers} = [ map { {
				id          => $_->{id},
				name        => $_->{name},
				coverage    => $_->{coverage},
				enabled     => $_->{enabled} ? 1 : 0,
				unavailable => $_->{unavailable},
			} } @$providers ];
			$params->{concert_provider_ids} = join(',', map { $_->{id} } @$providers);
		}

		$callback->($client, $params, $class->SUPER::handler($client, $params), @args);
	};
	my $listed = sub { $render->(shift->{providers}) };
	my $failed = sub { $render->() };

	# Talk to the address being saved, not the old one
	if ($params->{saveSettings} && $params->{pref_service_url}) {
		$prefs->set('service_url', $params->{pref_service_url});
	}

	if ($params->{saveSettings} && defined $params->{concert_provider_ids}) {
		my $enabled = join(',', grep { $params->{"concert_provider_$_"} } split(/,/, $params->{concert_provider_ids}));
		Plugins::DJ::Plugin::_callService('POST', '/concerts/providers', { enabled => $enabled }, sub {
			Plugins::DJ::Plugin::concertSourcesChanged();
			$listed->(@_);
		}, $failed);
	}
	else {
		Plugins::DJ::Plugin::_callService('GET', '/concerts/providers', {}, $listed, $failed);
	}

	return;
}

1;
