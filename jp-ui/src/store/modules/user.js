export default {
  namespaced: true,
  state: {
    id: '',
    name: '',
    loginName: '',
    no: '',
    office: {
      id: '',
      name: ''
    },
    company: {
      id: '',
      name: ''
    },
    photo: ''
  },
  mutations: {
    updateId (state, id) {
      state.id = id
    },
    updateName (state, name) {
      state.name = name
    },
    updateLoginName (state, loginName) {
      state.loginName = loginName
    },
    updatePhoto (state, photo) {
      state.photo = photo
    },
    updateUser (state, user) {
      state.id = user.id
      state.name = user.name
      state.loginName = user.loginName
      state.company = user.companyDTO
      state.office = user.officeDTO
      state.no = user.no
      state.photo = user.photo
      localStorage.setItem('user', JSON.stringify(user))
    }
  }
}
