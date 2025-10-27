var json_string = process.argv[2];
var season_num = process.argv[4] ? process.argv[4] : 0;
var voice_num = process.argv[3] ? process.argv[3] : 0;

var urls = [];
var json = JSON.parse(json_string);

for (var key1 in json) {

    if (key1 == voice_num) {
        for (var key2 in json[key1].folder) {
            if (key2 == season_num) {
                for (var key3 in json[key1].folder[key2].folder) {
                    urls.push(json[key1].folder[key2].folder[key3].file);
                }
            }
            
        }
    }
}

console.log(urls.join(','));