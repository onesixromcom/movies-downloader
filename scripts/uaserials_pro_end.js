var urls = [];

for (season_idx in player.results) {
  if (season_name !== player.results[season_idx].title) continue;
    for (series_idx in player.results[season_idx].folder)
        urls.push(player.results[season_idx].folder[series_idx].folder[0].file); 
}

console.log(urls.join(','));