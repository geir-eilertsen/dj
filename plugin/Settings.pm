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

1;
