const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

module.exports = context => {
    context.document.addEventListener ||= () => {};
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../../main/resources/static/js/ui-events.js'), 'utf8'), context);
};
