// Use fake Plajerjs class to handle object data from <script> tag.
class Playerjs {
   constructor(data) {
      this.data = data;
      this.results = JSON.parse(data.file);
   }
}

let season_name = process.argv[2]; 

if (season_name == undefined) {
  return;
}
