#!/usr/bin/env perl
;; Copyright (C) 2025 Dan Anderson
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as
;; published by the Free Software Foundation, either version 3 of the
;; License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

use strict;
use warnings;
use CGI qw(:standard -utf8);
use JSON qw(decode_json);
use File::Basename qw(dirname);
use Cwd qw(abs_path);

# Resolve repo root (assumes this script lives in cgi/ under the repo).
my $script_dir = dirname(abs_path($0));
my $root       = dirname($script_dir);
my $milla      = "$root/bin/milla";
my $reset      = "$root/bin/milla-reset-db";
my $dump       = "$root/bin/milla-dump-db";

my $action  = param('action')  || '';
my $prompt  = param('prompt')  || '';
my $model   = param('model')   || 'llama3.2';
my $message = '';
my $answer  = '';
my @models  = grep { $_ ne '' }
              map { (split(/\s+/, $_))[0] }
              split(/\n/, qx{ollama list 2>/dev/null});
@models = ('llama3.2') unless @models;

# Simple answer extraction: parse JSON if possible, else raw.
sub extract_answer {
  my ($raw) = @_;
  return '' unless defined $raw;
  eval {
    my $parsed = decode_json($raw);
    return $parsed->{answer} if exists $parsed->{answer};
  };
  if ($raw =~ /"answer"\s*:\s*"(.+?)"/s) {
    my $val = $1;
    $val =~ s/\\n/\n/g;
    $val =~ s/\\"/"/g;
    return $val;
  }
  return $raw;
}

# Handle reset
if ($action eq 'reset') {
  my $out = qx{$reset 2>&1};
  $message = "Reset requested.\n$out";
}
# Handle dump download
elsif ($action eq 'dump') {
  my $out = qx{$dump 2>&1};
  print header(-type => 'application/octet-stream',
               -attachment => 'milla_dump.txt');
  print $out;
  exit;
}
# Handle chat
elsif ($prompt ne '') {
  my $cmd = qq{$milla $model "$prompt" 2>&1};
  $answer = qx{$cmd};
  my $ans_only = extract_answer($answer);
  $message = "You: $prompt\n\nMilla: $ans_only";
}

print header(-type => 'text/html', -charset => 'utf-8');
print <<'HTML';
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Milla Chat</title>
  <style>
    body { font-family: "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace; margin: 0; display: flex; min-height: 100vh; }
    .sidebar { width: 240px; background: #f4f4f4; padding: 16px; box-sizing: border-box; border-right: 1px solid #ddd; }
    .main { flex: 1; padding: 24px; box-sizing: border-box; }
    textarea { width: 100%; height: 220px; font-family: monospace; }
    .buttons { margin-top: 12px; display: flex; gap: 8px; }
    .buttons button { padding: 8px 12px; }
    .hint { color: #666; font-size: 12px; margin-top: 6px; }
    .sidebar form { margin-bottom: 12px; }
  </style>
</head>
<body>
  <div class="sidebar">
    <h3>Milla Controls</h3>
    <div class="hint" style="margin-bottom:10px;">Powered by ARM — Designed to run on Raspberry Pi</div>
    <form method="post">
      <input type="hidden" name="action" value="reset">
      <button type="submit">Reset DB / Session</button>
    </form>
    <form method="post">
      <input type="hidden" name="action" value="dump">
      <button type="submit">Download DB Dump</button>
    </form>
    <div class="hint">Model defaults to llama3.2; change in the form if desired.</div>
  </div>
  <div class="main">
    <h2>Milla Chat (via CLI)</h2>
    <form method="post">
      <input type="hidden" name="action" value="chat">
      <label>Model:
        <select name="model" onchange="this.form.submit()">
HTML
for my $m (@models) {
  my $sel = ($m eq $model) ? 'selected' : '';
  print qq{          <option value="} . escapeHTML($m) . qq{" $sel>} . escapeHTML($m) . qq{</option>\n};
}
print <<'HTML';
        </select>
      </label>
      <br><br>
      <label for="prompt">Message to Milla:</label><br>
      <textarea id="prompt" name="prompt" placeholder="Type your message...">
HTML
print escapeHTML($prompt);
print <<'HTML';
</textarea>
      <div class="buttons">
        <button type="submit">Send</button>
      </div>
    </form>
    <pre style="white-space: pre; margin-top: 16px;">
   ~ milla fleurs ~
      LLM Server

⠀⠀⠀⠀⢀⠠⠤⠀⢀⣿⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀
⠀⠀⠐⠀⠐⠀⠀⢀⣾⣿⡇⠀⠀⠀⠀⠀⢀⣼⡇⠀⠀⠀⠀
⠀⠀⠀⠀⠀⠀⠀⣸⣿⣿⣿⠀⠀⠀⠀⣴⣿⣿⠇⠀⠀⠀⠀
⠀⠀⠀⠀⠀⠀⢠⣿⣿⣿⣇⠀⠀⢀⣾⣿⣿⣿⠀⠀⠀⠀⠀
⠀⠀⠀⠀⠀⣴⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⡟⠀⠀⠐⠀⡀
⠀⠀⠀⠀⢰⡿⠉⠀⡜⣿⣿⣿⡿⠿⢿⣿⣿⡃⠀⠀⠂⠄⠀
⠀⠀⠒⠒⠸⣿⣄⡘⣃⣿⣿⡟⢰⠃⠀⢹⣿⡇⠀⠀⠀⠀⠀
⠀⠀⠚⠉⠀⠊⠻⣿⣿⣿⣿⣿⣮⣤⣤⣿⡟⠁⠘⠠⠁⠀⠀
⠀⠀⠀⠀⠀⠠⠀⠀⠈⠙⠛⠛⠛⠛⠛⠁⠀⠒⠤⠀⠀⠀⠀
⠨⠠⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠑⠀⠀⠀⠀⠀⠀
⠁⠃⠉⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀

-- watching quietly over you --
    </pre>
    <h3>Last exchange</h3>
    <pre>
HTML
print escapeHTML($message);
print <<'HTML';
</pre>
  </div>
</body>
</html>
HTML
